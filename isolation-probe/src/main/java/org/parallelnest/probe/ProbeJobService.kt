package org.parallelnest.probe

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Process

class ProbeJobService: JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        getSharedPreferences("probe",MODE_PRIVATE).edit().putString("job_result","JobScheduler callback · UID ${Process.myUid()} · ${System.currentTimeMillis()}").commit()
        return false
    }
    override fun onStopJob(params: JobParameters)=true
}
