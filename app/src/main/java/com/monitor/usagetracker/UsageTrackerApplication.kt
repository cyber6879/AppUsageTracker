package com.monitor.usagetracker

import android.app.Application
import com.monitor.usagetracker.data.collector.PackageMetadataResolver
import com.monitor.usagetracker.data.collector.UsageEventParser
import com.monitor.usagetracker.data.export.DataExportManager
import com.monitor.usagetracker.data.local.AppUsageDatabase
import com.monitor.usagetracker.data.repository.UsageRepositoryImpl
import com.monitor.usagetracker.data.worker.UsageSyncWorker
import com.monitor.usagetracker.domain.repository.UsageRepository

class UsageTrackerApplication : Application() {

    lateinit var database: AppUsageDatabase
        private set

    lateinit var metadataResolver: PackageMetadataResolver
        private set

    lateinit var usageRepository: UsageRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppUsageDatabase.getInstance(this)
        metadataResolver = PackageMetadataResolver(this)
        val eventParser = UsageEventParser(this, metadataResolver)
        val exportManager = DataExportManager(this)

        usageRepository = UsageRepositoryImpl(
            context = this,
            usageDao = database.usageDao(),
            metadataResolver = metadataResolver,
            eventParser = eventParser,
            exportManager = exportManager
        )

        UsageSyncWorker.enqueuePeriodicSync(this)
    }
}
