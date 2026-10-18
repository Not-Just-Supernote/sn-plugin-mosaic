#include <jni.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

/* Panel completion observation; all mappings in this probe are read-only. */
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
