package com.thiepn.scan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM processing_presets ORDER BY updatedAt DESC, name COLLATE NOCASE")
    fun observePresets(): Flow<List<ProcessingPresetEntity>>

    @Query("SELECT * FROM workflow_rules ORDER BY priority ASC, updatedAt DESC")
    fun observeRules(): Flow<List<WorkflowRuleEntity>>

    @Query("SELECT * FROM workflow_destinations ORDER BY updatedAt DESC, name COLLATE NOCASE")
    fun observeDestinations(): Flow<List<WorkflowDestinationEntity>>

    @Query("SELECT * FROM workflow_runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int = 100): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM processing_presets WHERE id = :id LIMIT 1")
    suspend fun getPreset(id: String): ProcessingPresetEntity?

    @Query("SELECT * FROM processing_presets ORDER BY updatedAt DESC, name COLLATE NOCASE")
    suspend fun getPresets(): List<ProcessingPresetEntity>

    @Query("SELECT * FROM workflow_rules WHERE id = :id LIMIT 1")
    suspend fun getRule(id: String): WorkflowRuleEntity?

    @Query(
        "SELECT * FROM workflow_rules WHERE enabled = 1 AND trigger = :trigger " +
            "ORDER BY priority ASC, createdAt ASC"
    )
    suspend fun getEnabledRules(trigger: String): List<WorkflowRuleEntity>

    @Query("SELECT * FROM workflow_destinations WHERE id = :id LIMIT 1")
    suspend fun getDestination(id: String): WorkflowDestinationEntity?

    @Query("SELECT * FROM workflow_runs WHERE id = :id LIMIT 1")
    suspend fun getRun(id: String): WorkflowRunEntity?

    @Query(
        "SELECT * FROM workflow_runs WHERE documentId = :documentId " +
            "AND ruleId = :ruleId AND trigger = :trigger " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun getLatestRuleRun(
        documentId: String,
        ruleId: String,
        trigger: String
    ): WorkflowRunEntity?

    @Query(
        "SELECT * FROM workflow_runs WHERE status = 'PENDING' " +
            "OR (status = 'FAILED' AND nextRetryAt IS NOT NULL AND nextRetryAt <= :now) " +
            "ORDER BY startedAt ASC LIMIT :limit"
    )
    suspend fun getRunnableRuns(now: Long, limit: Int = 50): List<WorkflowRunEntity>

    @Query(
        "SELECT * FROM workflow_runs WHERE status = 'FAILED' AND nextRetryAt IS NOT NULL " +
            "ORDER BY nextRetryAt ASC LIMIT 1"
    )
    suspend fun getNextRetryRun(): WorkflowRunEntity?

    @Query(
        "UPDATE workflow_runs SET status = 'FAILED', finishedAt = :now, " +
            "nextRetryAt = :now, summary = 'Interrupted; retry queued', " +
            "lastError = 'Workflow was interrupted before completion.' " +
            "WHERE status = 'RUNNING'"
    )
    suspend fun resetInterruptedRuns(now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: ProcessingPresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: WorkflowRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDestination(destination: WorkflowDestinationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: WorkflowRunEntity)

    @Query("DELETE FROM processing_presets WHERE id = :id")
    suspend fun deletePreset(id: String)

    @Query("DELETE FROM workflow_rules WHERE presetId = :presetId")
    suspend fun deleteRulesForPreset(presetId: String)

    @Query("DELETE FROM workflow_rules WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("DELETE FROM workflow_destinations WHERE id = :id")
    suspend fun deleteDestination(id: String)

    @Query("DELETE FROM workflow_runs WHERE documentId = :documentId")
    suspend fun deleteRunsForDocument(documentId: String)

    @Query(
        "UPDATE workflow_rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setRuleEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE workflow_runs SET outputUri = :outputUri WHERE id = :id")
    suspend fun setRunOutputUri(id: String, outputUri: String?)

    @Query(
        "UPDATE workflow_runs SET status = :status, attemptCount = :attemptCount, " +
            "finishedAt = :finishedAt, nextRetryAt = :nextRetryAt, summary = :summary, " +
            "lastError = :lastError WHERE id = :id"
    )
    suspend fun updateRunState(
        id: String,
        status: String,
        attemptCount: Int,
        finishedAt: Long?,
        nextRetryAt: Long?,
        summary: String,
        lastError: String?
    )
}
