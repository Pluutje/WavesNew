package app.aaps.core.keys

enum class IntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false
) : IntPreferenceKey {

    OverviewCarbsButtonIncrement1("carbs_button_increment_1", 5, -50, 50, defaultedBySM = true, dependency = BooleanKey.OverviewShowCarbsButton),
    OverviewCarbsButtonIncrement2("carbs_button_increment_2", 10, -50, 50, defaultedBySM = true, dependency = BooleanKey.OverviewShowCarbsButton),
    OverviewCarbsButtonIncrement3("carbs_button_increment_3", 20, -50, 50, defaultedBySM = true, dependency = BooleanKey.OverviewShowCarbsButton),
    OverviewEatingSoonDuration("eatingsoon_duration", 45, 15, 120, defaultedBySM = true, hideParentScreenIfHidden = true),
    OverviewActivityDuration("activity_duration", 90, 15, 600, defaultedBySM = true),
    OverviewHypoDuration("hypo_duration", 60, 15, 180, defaultedBySM = true),
    OverviewCageWarning("statuslights_cage_warning", 48, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewCageCritical("statuslights_cage_critical", 72, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewIageWarning("statuslights_iage_warning", 72, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewIageCritical("statuslights_iage_critical", 144, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSageWarning("statuslights_sage_warning", 216, 24, 720, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSageCritical("statuslights_sage_critical", 240, 24, 720, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSbatWarning("statuslights_sbat_warning", 25, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSbatCritical("statuslights_sbat_critical", 5, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBageWarning("statuslights_bage_warning", 216, 24, 1000, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBageCritical("statuslights_bage_critical", 240, 24, 1000, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewResWarning("statuslights_res_warning", 80, 0, 300, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewResCritical("statuslights_res_critical", 10, 0, 300, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBattWarning("statuslights_bat_warning", 51, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBattCritical("statuslights_bat_critical", 26, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBolusPercentage("boluswizard_percentage", 100, 10, 100),
    OverviewResetBolusPercentageTime("key_reset_boluswizard_percentage_time", 16, 6, 120, defaultedBySM = true, engineeringModeOnly = true),
    ProtectionTimeout("protection_timeout", 1, 1, 180, defaultedBySM = true),
    ProtectionTypeSettings("settings_protection", 0, 0, 5),
    ProtectionTypeApplication("application_protection", 0, 0, 5),
    ProtectionTypeBolus("bolus_protection", 0, 0, 5),
    SafetyMaxCarbs("treatmentssafety_maxcarbs", 48, 1, 200),
    LoopOpenModeMinChange("loop_openmode_min_change", 30, 0, 50, defaultedBySM = true),
    ApsMaxSmbFrequency("smbinterval", 3, 1, 10, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsMaxMinutesOfBasalToLimitSmb("smbmaxminutes", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsUamMaxMinutesOfBasalToLimitSmb("uamsmbmaxminutes", 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsCarbsRequestThreshold("carbsReqThreshold", 1, 1, 10, defaultedBySM = true),
    ApsAutoIsfHalfBasalExerciseTarget("half_basal_exercise_target", 160, 120, 200, defaultedBySM = true),
    ApsAutoIsfIobThPercent("iob_threshold_percent", 100, 10, 100, defaultedBySM = true),
    ApsDynIsfAdjustmentFactor("DynISFAdjust", 100, 1, 300, dependency = BooleanKey.ApsUseDynamicSensitivity),
    ApsWaveMaxMinutesOfBasalToLimitSmb("wavesmbmaxminutes", 60, 15, 240, defaultedBySM = true, dependency = BooleanKey.ApsWaveEnable, negativeDependency = BooleanKey.ApsWaveUseSMBCap),
    ApsWaveStartTime("wave_start", 11, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsWaveEnable),
    ApsWaveEndTime("wave_end", 21, 0, 23, defaultedBySM = true, dependency = BooleanKey.ApsWaveEnable),

    AutosensPeriod("openapsama_autosens_period", 24, 4, 24, calculatedDefaultValue = true),
    MaintenanceLogsAmount("maintenance_logs_amount", 2, 1, 10, defaultedBySM = true),
    AlertsStaleDataThreshold("missed_bg_readings_threshold", 30, 15, 10000, defaultedBySM = true, dependency = BooleanKey.AlertMissedBgReading),
    AlertsPumpUnreachableThreshold("pump_unreachable_threshold", 30, 30, 300, defaultedBySM = true, dependency = BooleanKey.AlertPumpUnreachable),
    InsulinOrefPeak("insulin_oref_peak", 75, 35, 120, hideParentScreenIfHidden = true),

    AutotuneDefaultTuneDays("autotune_default_tune_days", 5, 1, 30),

    // AutoExportPasswordExpiryDays("auto_export_password_expiry_days", 28, 7, 28),

    SmsRemoteBolusDistance("smscommunicator_remotebolusmindistance", 15, 3, 60),

    BgSourceRandomInterval("randombg_interval_min", 5, 1, 15, defaultedBySM = true),
    GarminLocalHttpPort("communication_http_port", 28891, 1001, 65535, defaultedBySM = true, hideParentScreenIfHidden = true),
    NsClientAlarmStaleData("ns_alarm_stale_data_value", 16, 15, 120),
    NsClientUrgentAlarmStaleData("ns_alarm_urgent_stale_data_value", 31, 30, 180),

    // eigen
    Min_resistentiePerc("Min_resistentiePerc", 80,10,100),
    Max_resistentiePerc("Max_resistentiePerc", 120,100,200),
    Dag_resistentiePerc("Dag_resistentiePerc", 100,10,200),
    Nacht_resistentiePerc("Nacht_resistentiePerc", 100,10,200),
    Dagen_resistentie("Dagen_resistentie", 3,1,7),
    // Uren_resistentie("Uren_resistentie", 2,1,5),

    stap_activiteteitPerc("stap_activiteteitPerc", 50,10,100),
    stap_5minuten("stap_5minuten", 200,100,300),
    stap_retentie("stap_retentie", 6,2,12),

    Dag_MaxPersistentPerc("Dag_MaxPersistentPerc", 120,100,200),
    Nacht_MaxPersistentPerc("Nacht_MaxPersistentPerc", 120,100,200),


}