#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

typedef struct {
    int32_t left, top, right, bottom;
    uint32_t buffer_offset;
    uint8_t mode, flags;
    uint16_t reserved;
} DisplayArea;
_Static_assert(sizeof(DisplayArea)==24, "driver area ABI");

/* Full-refresh completion observation; all mappings in this probe are read-only. */
JNIEXPORT jint JNICALL
Java_me_laumss_mosaic_eink_EinkRegionDriver_displayBusyNative(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    uint32_t info[64]={0};
    int result=-1;
    int fd=open("/dev/ebc",O_RDONLY|O_CLOEXEC);
    if(fd<0) return -1;
    if(ioctl(fd,0x48545201UL,info)!=0 || info[0]!=0x021f0508 ||
       info[1]!=0x0a000780 || info[2]!=1920 || info[3]!=4915200 ||
       (info[5]&65535)!=8) goto done_busy;
    size_t frame=info[3];
    const volatile uint8_t *planes=mmap(NULL,frame*7,PROT_READ,MAP_SHARED,fd,0);
    if(planes==MAP_FAILED) goto done_busy;
    result=0;
    for(size_t i=0;i<frame;i++) {
        if(planes[frame*6+i]) { result=1; break; }
    }
    munmap((void *)planes,frame*7);
done_busy:
    close(fd);
    return result;
}

/* Automatic cleanup uses a single masked pair for the whole exposed region.
 * JNI entry points are serialized on Android's main thread. The hold between
 * black and white is scheduled by Kotlin, so input can cancel and restore it.
 * Internal driver planes remain read-only. */
typedef struct {
    int fd;
    size_t frame;
    uint8_t *source;
    const volatile uint8_t *planes;
    uint8_t *mask;
    DisplayArea area;
    unsigned selected;
    jlong token;
    int white_submitted, white_rc;
    double black_submitted_ms;
} BlackBatch;
static BlackBatch batch={.fd=-1};
static jlong batch_sequence=0;
static char batch_result[512]="idle";

static void release_batch(void) {
    if(batch.source && batch.source!=MAP_FAILED) munmap(batch.source,batch.frame);
    if(batch.planes && batch.planes!=MAP_FAILED) munmap((void *)batch.planes,batch.frame*8);
    if(batch.fd>=0) close(batch.fd);
    free(batch.mask);
    memset(&batch,0,sizeof(batch));batch.fd=-1;
}
static double monotonic_ms(void) {
    struct timespec time;clock_gettime(CLOCK_MONOTONIC,&time);
    return time.tv_sec*1000.0+time.tv_nsec/1000000.0;
}
/* Device-mapped byte reads are expensive. Snapshot one row pair in a burst,
 * then inspect only owned pixels in ordinary RAM. Internal planes stay read-only. */
static unsigned count_batch_plane(int plane,int value,int first_only) {
    uint8_t row[3840];unsigned count=0;
    int left=batch.area.left,right=batch.area.right;
    for(int y=batch.area.top;y<batch.area.bottom;y+=2) {
        size_t base=(size_t)(y/2)*3840+(size_t)left*2;
        memcpy(row,(const void *)(batch.planes+batch.frame*(size_t)plane+base),(size_t)(right-left)*2);
        for(int parity=0;parity<2 && y+parity<batch.area.bottom;parity++) {
            size_t pixel=(size_t)(y+parity)*1920+left;
            for(int x=left;x<right;x++,pixel++) {
                if(!(batch.mask[pixel>>3]&(1u<<(pixel&7)))) continue;
                uint8_t sample=row[(x-left)*2+parity];
                if(value<0 ? sample!=0 : sample==value) {
                    if(first_only) return 1;
                    count++;
                }
            }
        }
    }
    return count;
}
static void finish_batch(void) {
    if(batch.white_submitted) return;
    double started=monotonic_ms();
    unsigned restored=0,changed=0;
    uint8_t row[1920];
    for(int y=batch.area.top;y<batch.area.bottom;y++) {
        int left=batch.area.left,right=batch.area.right,run=-1;
        size_t base=(size_t)y*1920;
        memcpy(row,batch.source+base+left,(size_t)(right-left));
        for(int x=left;x<=right;x++) {
            size_t i=base+x;
            int owned=x<right && (batch.mask[i>>3]&(1u<<(i&7)));
            int restore=owned && row[x-left]==0;
            if(restore) {if(run<0) run=x;restored++;}
            else {
                if(run>=0) {memset(batch.source+base+run,15,(size_t)(x-run));run=-1;}
                if(owned && row[x-left]!=15) changed++;
            }
        }
    }
    __sync_synchronize();
    double prepared=monotonic_ms();
    DisplayArea area=batch.area;
    int rc=ioctl(batch.fd,0x48545701UL,&area),saved_errno=rc<0?errno:0;
    double submitted=monotonic_ms();
    batch.white_submitted=1;batch.white_rc=rc;
    snprintf(batch_result,sizeof(batch_result),"white: token=%lld pixels=%u restored=%u sourceChanged=%u rc=%d errno=%d slot=%d restoreMs=%.2f ioctlMs=%.2f sinceBlackMs=%.2f",
        (long long)batch.token,batch.selected,restored,changed,rc,saved_errno,area.top,
        prepared-started,submitted-prepared,submitted-batch.black_submitted_ms);
}
JNIEXPORT jlong JNICALL
Java_me_laumss_mosaic_eink_EinkRegionDriver_beginBlackBatchNative(JNIEnv *env,jobject self,jintArray input) {
    (void)self;
    if(batch.token) {snprintf(batch_result,sizeof(batch_result),"skip: another cleanup owns the driver");return 0;}
    double started=monotonic_ms();
    jint rects[16384];uint32_t info[64]={0};
    jsize n=input?(*env)->GetArrayLength(env,input):0;
    snprintf(batch_result,sizeof(batch_result),"skip: invalid cleanup region");
    if(n<4 || n>16384 || n%4) return 0;
    (*env)->GetIntArrayRegion(env,input,0,n,rects);
    if((*env)->ExceptionCheck(env)) return 0;
    uint64_t candidate_area=0;
    for(int i=0;i<n;i+=4) {
        if(rects[i]<0 || rects[i+1]<0 || rects[i+2]>1920 || rects[i+3]>2560 || rects[i]>=rects[i+2] || rects[i+1]>=rects[i+3]) return 0;
        candidate_area+=(uint64_t)(rects[i+2]-rects[i])*(rects[i+3]-rects[i+1]);
    }
    if(candidate_area>4915200) return 0;
    batch.fd=open("/dev/ebc",O_RDWR|O_CLOEXEC);
    if(batch.fd<0) {snprintf(batch_result,sizeof(batch_result),"error: open errno=%d",errno);goto abort;}
    if(ioctl(batch.fd,0x48545201UL,info)!=0) {snprintf(batch_result,sizeof(batch_result),"error: query errno=%d",errno);goto abort;}
    if(info[0]!=0x021f0508 || info[1]!=0x0a000780 || info[2]!=1920 || info[3]!=4915200 || (info[5]&65535)!=8) {
        snprintf(batch_result,sizeof(batch_result),"skip: unsupported cleanup layout");goto abort;
    }
    batch.frame=info[3];
    batch.planes=mmap(NULL,batch.frame*8,PROT_READ,MAP_SHARED,batch.fd,0);
    if(batch.planes==MAP_FAILED) {snprintf(batch_result,sizeof(batch_result),"error: diagnostic mmap errno=%d",errno);goto abort;}
    batch.source=mmap(NULL,batch.frame,PROT_READ|PROT_WRITE,MAP_SHARED,batch.fd,0);
    if(batch.source==MAP_FAILED) {snprintf(batch_result,sizeof(batch_result),"error: source mmap errno=%d",errno);goto abort;}
    batch.mask=calloc((batch.frame+7)/8,1);
    if(!batch.mask) {snprintf(batch_result,sizeof(batch_result),"error: cleanup mask allocation");goto abort;}
    int scan_left=1920,scan_top=2560,scan_right=0,scan_bottom=0;
    // Union the requested regions in RAM first, including overlapping rectangles.
    for(int r=0;r<n;r+=4) {
        if(rects[r]<scan_left) scan_left=rects[r];if(rects[r+1]<scan_top) scan_top=rects[r+1];
        if(rects[r+2]>scan_right) scan_right=rects[r+2];if(rects[r+3]>scan_bottom) scan_bottom=rects[r+3];
        for(int y=rects[r+1];y<rects[r+3];y++) for(int x=rects[r];x<rects[r+2];x++) {
            size_t i=(size_t)y*1920+x;batch.mask[i>>3]|=1u<<(i&7);
        }
    }
    int left=1920,top=2560,right=0,bottom=0;
    unsigned occupied=0,unsettled=0;
    uint8_t source_row[1920],history_row[3840],busy_row[3840];
    size_t width=(size_t)(scan_right-scan_left);
    // Gate on actual completion of the preceding frame before changing source.
    for(int y=scan_top;y<scan_bottom;y++) {
        size_t base=(size_t)y*1920,processing=(size_t)(y/2)*3840+(size_t)scan_left*2;
        memcpy(source_row,batch.source+base+scan_left,width);
        memcpy(history_row,(const void *)(batch.planes+batch.frame*2+processing),width*2);
        memcpy(busy_row,(const void *)(batch.planes+batch.frame*6+processing),width*2);
        for(int x=scan_left;x<scan_right;x++) {
            size_t i=base+x,p=(size_t)(x-scan_left)*2+(y&1);
            if(!(batch.mask[i>>3]&(1u<<(i&7)))) continue;
            if(source_row[x-scan_left]!=15 && source_row[x-scan_left]!=0x8f) {
                occupied++;batch.mask[i>>3]&=(uint8_t)~(1u<<(i&7));
            } else if(history_row[p]!=15 || busy_row[p]!=0) unsettled++;
        }
        if(unsettled) {snprintf(batch_result,sizeof(batch_result),"wait: previous frame unsettled=%u",unsettled);goto abort;}
    }
    __sync_synchronize();
    // Recheck each row immediately before writing its eligible spans.
    for(int y=scan_top;y<scan_bottom;y++) {
        size_t base=(size_t)y*1920,processing=(size_t)(y/2)*3840+(size_t)scan_left*2;
        memcpy(source_row,batch.source+base+scan_left,width);
        memcpy(history_row,(const void *)(batch.planes+batch.frame*2+processing),width*2);
        memcpy(busy_row,(const void *)(batch.planes+batch.frame*6+processing),width*2);
        int run=-1;
        for(int x=scan_left;x<=scan_right;x++) {
            size_t i=base+x;
            int eligible=x<scan_right && (batch.mask[i>>3]&(1u<<(i&7)));
            if(eligible) {
                size_t p=(size_t)(x-scan_left)*2+(y&1);
                if(source_row[x-scan_left]!=15 && source_row[x-scan_left]!=0x8f) {occupied++;eligible=0;}
                else if(history_row[p]!=15 || busy_row[p]!=0) {unsettled++;eligible=0;}
                if(!eligible) batch.mask[i>>3]&=(uint8_t)~(1u<<(i&7));
            }
            if(eligible) {
                batch.selected++;if(run<0) run=x;
                if(x<left) left=x;if(y<top) top=y;if(x+1>right) right=x+1;if(y+1>bottom) bottom=y+1;
            } else if(run>=0) {
                memset(batch.source+base+run,0,(size_t)(x-run));run=-1;
            }
        }
    }
    if(!batch.selected) {snprintf(batch_result,sizeof(batch_result),"skip: no settled white pixels occupied=%u unsettled=%u",occupied,unsettled);goto abort;}
    batch.area=(DisplayArea){.left=left&~7,.top=top&~7,.right=(right+7)&~7,.bottom=(bottom+7)&~7,.mode=7};
    batch.token=++batch_sequence;
    __sync_synchronize();
    double prepared=monotonic_ms();
    DisplayArea area=batch.area;
    int rc=ioctl(batch.fd,0x48545701UL,&area),saved_errno=rc<0?errno:0;
    batch.black_submitted_ms=monotonic_ms();
    if(rc<0) {
        finish_batch();
        char recovery[512];snprintf(recovery,sizeof(recovery),"%s",batch_result);
        release_batch();
        snprintf(batch_result,sizeof(batch_result),"error: black rc=%d errno=%d; %.450s",rc,saved_errno,recovery);
        return 0;
    }
    snprintf(batch_result,sizeof(batch_result),"black: token=%lld regions=%d pixels=%u occupied=%u unsettled=%u rect=%d,%d,%d,%d rc=%d slot=%d prepareMs=%.2f ioctlMs=%.2f",
        (long long)batch.token,n/4,batch.selected,occupied,unsettled,batch.area.left,batch.area.top,batch.area.right,batch.area.bottom,rc,area.top,prepared-started,batch.black_submitted_ms-prepared);
    return batch.token;
abort:
    release_batch();return 0;
}
JNIEXPORT jstring JNICALL
Java_me_laumss_mosaic_eink_EinkRegionDriver_finishBlackBatchNative(JNIEnv *env,jobject self,jlong token) {
    (void)self;
    if(token && token==batch.token) finish_batch();
    else return (*env)->NewStringUTF(env,"skip: expired cleanup token");
    return (*env)->NewStringUTF(env,batch_result);
}
JNIEXPORT jint JNICALL
Java_me_laumss_mosaic_eink_EinkRegionDriver_batchBusyNative(JNIEnv *env,jobject self,jlong token) {
    (void)env;(void)self;
    if(!token || token!=batch.token) return -1;
    return (jint)count_batch_plane(6,-1,1);
}
JNIEXPORT jstring JNICALL
Java_me_laumss_mosaic_eink_EinkRegionDriver_completeBatchNative(JNIEnv *env,jobject self,jlong token) {
    (void)self;
    if(!token || token!=batch.token) return (*env)->NewStringUTF(env,"skip: expired cleanup token");
    finish_batch();
    unsigned white=count_batch_plane(2,15,0);
    int busy=Java_me_laumss_mosaic_eink_EinkRegionDriver_batchBusyNative(env,self,token);
    snprintf(batch_result,sizeof(batch_result),"released: token=%lld pixels=%u whiteRc=%d historyWhite=%u busy=%d",
        (long long)token,batch.selected,batch.white_rc,white,busy);
    release_batch();
    return (*env)->NewStringUTF(env,batch_result);
}
JNIEXPORT jstring JNICALL
Java_me_laumss_mosaic_eink_EinkRegionDriver_batchResultNative(JNIEnv *env,jobject self) {
    (void)self;return (*env)->NewStringUTF(env,batch_result);
}
