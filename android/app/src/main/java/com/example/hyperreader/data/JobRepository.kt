package com.example.hyperreader.data

import com.example.hyperreader.model.ExportJob
import com.example.hyperreader.model.JobError
import com.example.hyperreader.model.JobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

class JobRepository(private val rootDirectory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jobsDirectory = File(rootDirectory, "jobs").apply { mkdirs() }

    suspend fun loadAll(): List<ExportJob> = withContext(Dispatchers.IO) {
        jobsDirectory.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            runCatching {
                val job = json.decodeFromString<ExportJob>(file.readText())
                if (job.status == JobStatus.queued || job.status == JobStatus.running) {
                    job.copy(
                        status = JobStatus.failed,
                        error = JobError("APP_INTERRUPTED", "上次任务因应用被系统终止而中断，请重新生成。"),
                        finishedAt = Instant.now().toString(),
                        progress = job.progress.copy(phase = com.example.hyperreader.model.JobPhase.failed, message = "任务已中断"),
                    ).also { persistBlocking(it) }
                } else job
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    suspend fun save(job: ExportJob): Unit = withContext(Dispatchers.IO) { persistBlocking(job) }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) { deleteBlocking(id) }

    // 与 persistBlocking 共用同一把锁：否则删除后紧接的一次进度写入会把记录复活。
    @Synchronized
    private fun deleteBlocking(id: String) {
        File(jobsDirectory, "$id.json").delete()
        File(jobsDirectory, "$id.json.tmp").delete()
    }

    fun readBlocking(job: ExportJob): ExportJob = job

    // cancel 与任务进度更新可能并发写同一条记录；共用 .tmp 文件时
    // 未同步会出现写一半被另一线程 rename 的竞态。
    @Synchronized
    private fun persistBlocking(job: ExportJob) {
        val target = File(jobsDirectory, "${job.id}.json")
        val temporary = File(jobsDirectory, "${job.id}.json.tmp")
        temporary.writeText(json.encodeToString(ExportJob.serializer(), job))
        if (!temporary.renameTo(target)) {
            target.writeText(temporary.readText())
            temporary.delete()
        }
    }
}
