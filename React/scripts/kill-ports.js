import { execSync } from 'child_process'

const PORTS = [3790, 3791]

for (const port of PORTS) {
  try {
    const result = execSync(`netstat -ano | findstr ":${port} "`, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] })
    const lines = result.trim().split('\n')
    for (const line of lines) {
      const parts = line.trim().split(/\s+/)
      const pid = parts[parts.length - 1]
      if (pid && pid !== '0') {
        try {
          execSync(`taskkill /PID ${pid} /F`, { stdio: 'ignore' })
          console.log(`Killed PID ${pid} on port ${port}`)
        } catch {}
      }
    }
  } catch {}
}
