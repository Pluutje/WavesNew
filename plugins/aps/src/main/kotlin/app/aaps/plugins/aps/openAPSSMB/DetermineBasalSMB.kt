package app.aaps.plugins.aps.openAPSSMB

import android.os.Environment
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.plugins.aps.R
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import java.io.File
import java.io.IOException
import java.util.Scanner
import java.time.LocalDate
import java.time.format.DateTimeFormatter


@Singleton
data class uur_minuut(val uur: Int, val minuut: Int)
data class Resistentie_class(val resistentie: Double, val log: String)
data class Stappen_class(val StapPercentage: Float,val StapTarget: Float, val log: String)
data class Persistent_class(val PercistentPercentage: Double, val log: String)
data class UAMBoost_class(val UAMBoostPercentage: Double, val log: String)
data class Bolus_SMB(val BolusViaSMB: Boolean, val ExtraSMB: Float, val ResterendAantalSMB: Int)
data class Extra_Insuline(val ExtraIns_AanUit: Boolean, val ExtraIns_waarde: Double ,val log: String)

class DetermineBasalSMB @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val iobCobCalculator: IobCobCalculator,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,
    //

) {

    private var StapRetentie: Int = 0
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
    private val ActExtraIns = File(externalDir, "ANALYSE/Act-extra-ins.txt")
    private val Bolus_SMB = File(externalDir, "ANALYSE/Bolus-via-smb.txt")
    private val LaatsteSMBFractie = File(externalDir, "ANALYSE/laatsteSMBFractie.txt")
   // private val csvfile = File(externalDir, "ANALYSE/analyse.csv")
    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()
    private var bolus_via_basaal = false
    private var lastUAMBoostTime: Long = 0L
    private var laatst_verstrekte_SMBfractie = -1
    private var vorige_bolus_tijdstip = -1

    private fun consoleLog(msg: String) {
        consoleLog.add(msg)
    }

    private fun consoleError(msg: String) {
        consoleError.add(msg)
        consoleLog("ERROR: $msg")
    }

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))

    fun round_basal(value: Double): Double = value

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int = value.roundToInt()

    // we expect BG to rise or fall at the rate of BGI,
    // adjusted by the rate at which BG would need to rise /
    // fall to get eventualBG to target over 2 hours
    fun calculate_expected_delta(targetBg: Double, eventualBg: Double, bgi: Double): Double {
        // (hours * mins_per_hour) / 5 = how many 5 minute periods in 2h = 24
        val fiveMinBlocks = (2 * 60) / 5
        val targetDelta = targetBg - eventualBg
        return /* expectedDelta */ round(bgi + (targetDelta / fiveMinBlocks), 1)
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")
    //DecimalFormat("0.#").format(profileUtil.fromMgdlToUnits(value))
    //if (profile.out_units === "mmol/L") round(value / 18, 1).toFixed(1);
    //else Math.round(value);

    fun enable_smb(profile: OapsProfile, microBolusAllowed: Boolean, meal_data: MealData, target_bg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
            consoleError("SMB disabled due to high temptarget of $target_bg")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError("SMB enabled due to enableSMB_always")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (profile.enableSMB_with_COB && meal_data.mealCOB != 0.0) {
            consoleError("SMB enabled for COB of ${meal_data.mealCOB}")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
        // (6 hours is defined in carbWindow in lib/meal/total.js)
        if (profile.enableSMB_after_carbs && meal_data.carbs != 0.0) {
            consoleError("SMB enabled for 6h after carb entry")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (profile.enableSMB_with_temptarget && (profile.temptargetSet && target_bg < 100)) {
            consoleError("SMB enabled for temptarget of ${convert_bg(target_bg)}")
            return true
        }

        consoleError("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return true
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError(msg)
    }

    private fun getMaxSafeBasal(profile: OapsProfile): Double {
        if (!bolus_via_basaal) {
            return min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))
        } else {
            return 25.0}
    }

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfile, rT: RT, currenttemp: CurrentTemp): RT {
        //var maxSafeBasal = Math.min(profile.max_basal, 3 * profile.max_daily_basal, 4 * profile.current_basal);

        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate
        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = round_basal(rate)
        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 && suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 && duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
            return rT
        }

        if (suggestedRate == profile.current_basal) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                    return rT
                } else {
                    reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                    return rT
                }
            } else {
                reason(rT, "Setting neutral temp basal of ${profile.current_basal}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
                return rT
            }
        } else {
            rT.duration = duration
            rT.rate = suggestedRate
            return rT
        }
    }

    // Functie om te controleren of de huidige tijd binnen het tijdsbereik valt
    fun isInTijdBereik(hh: Int, mm: Int, startUur: Int, startMinuut: Int, eindUur: Int, eindMinuut: Int): Boolean {
        val startInMinuten = startUur * 60 + startMinuut
        val eindInMinuten = eindUur * 60 + eindMinuut
        val huidigeTijdInMinuten = hh * 60 + mm

        // Als het eindtijdstip voor middernacht is (bijvoorbeeld van 23:00 tot 05:00), moeten we dat apart behandelen
        return if (eindInMinuten < startInMinuten) {
            // Tijdsbereik over de middernacht (bijvoorbeeld 23:00 tot 05:00)
            huidigeTijdInMinuten >= startInMinuten || huidigeTijdInMinuten < eindInMinuten
        } else {
            // Normale tijdsbereik (bijvoorbeeld van 08:00 tot 17:00)
            huidigeTijdInMinuten in startInMinuten..eindInMinuten
        }
    }

    fun refreshTime() : uur_minuut {
        val calendarInstance = Calendar.getInstance() // Nieuwe tijd ophalen

        val uur = calendarInstance[Calendar.HOUR_OF_DAY]
        val minuut = calendarInstance[Calendar.MINUTE]
        return uur_minuut(uur,minuut)
    }



    fun calculateCorrectionFactor(bgGem: Double, targetProfiel: Double, macht: Double, rel_std: Int): Double {
        var rel_std_cf = 1.0
        if (bgGem > targetProfiel) {
            rel_std_cf = 1.0/rel_std + 1.0
        }
        var cf = Math.pow(bgGem / (targetProfiel), macht) * rel_std_cf
        if (cf < 0.1) cf = 1.0

        return cf
    }

    fun logBgHistoryWithStdDev(startHour: Long, endHour: Long, uren: Long): Pair<Double, Double> {
        // Constants
        val MIN_READINGS_PER_HOUR = 8
        val MG_DL_TO_MMOL_L_CONVERSION = 18.0

        // Bereken start- en eindtijd
        val now = dateUtil.now()
        val startTime = now - T.hours(hour = startHour).msecs()
        val endTime = now - T.hours(hour = endHour).msecs()

        // Haal bloedglucosewaarden op
        val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)

        // Controleer of er voldoende data is
        if (bgReadings.size < MIN_READINGS_PER_HOUR * uren) {
            return Pair(0.0, 0.0) // Onvoldoende data
        }

        // Bereken gemiddelde in mmol/L
        val totalBgValue = bgReadings.sumOf { it.value }
        val bgAverage = (totalBgValue / bgReadings.size) / MG_DL_TO_MMOL_L_CONVERSION

        // Bereken variantie en standaarddeviatie
        val variance = bgReadings.sumOf {
            val bgInMmol = it.value / MG_DL_TO_MMOL_L_CONVERSION
            (bgInMmol - bgAverage) * (bgInMmol - bgAverage)
        } / bgReadings.size

        val stdDev = Math.sqrt(variance)

        return Pair(bgAverage, stdDev)
    }

    fun WaveActief(): Boolean {
        val Startweek = preferences.get(StringKey.ApsWaveStartTijd)
        val Startweekend = preferences.get(StringKey.ApsWaveStartTijdWeekend)
        val End = preferences.get(StringKey.ApsWaveEndTijd)
        val WeekendDagen = preferences.get(StringKey.WeekendDagen)
        val (uur,minuten) = refreshTime()

        val dayMapping = mapOf(
            "ma" to Calendar.MONDAY,
            "di" to Calendar.TUESDAY,
            "wo" to Calendar.WEDNESDAY,
            "do" to Calendar.THURSDAY,
            "vr" to Calendar.FRIDAY,
            "za" to Calendar.SATURDAY,
            "zo" to Calendar.SUNDAY
        )

// Converteer de invoerstring naar een lijst van Calendar-dagen
        //    val weekendString = WeekendDagen
        val weekendDays = WeekendDagen.split(",")
            .mapNotNull { dayMapping[it.trim()] } // Map afkortingen naar Calendar-waarden en filter null-waarden

// Wijs de lijst toe aan profile.WeekendDays
        val calendarInstance = Calendar.getInstance() // Nieuwe tijd ophalen
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        val weekend = dayOfWeek in weekendDays


        val (StartUur, StartMinuut) = if (weekend) {
            Startweekend.split(":").map { it.toInt() }
        } else {
            Startweek.split(":").map { it.toInt() }
        }

        val (EndUur, EndMinuut) = End.split(":").map { it.toInt() }

        if (isInTijdBereik(uur, minuten, StartUur, StartMinuut, EndUur, EndMinuut)) {
            return true
        } else {
            return false
        }


    }

    fun Nacht():Boolean {
        val OchtendStart = preferences.get(StringKey.OchtendStart)
        val OchtendStartWeekend = preferences.get(StringKey.OchtendStartWeekend)
        val NachtStart = preferences.get(StringKey.NachtStart)
        val WeekendDagen = preferences.get(StringKey.WeekendDagen)
        // Dag - Nacht
        val (uurVanDag,minuten) = refreshTime()
        val minuutTxt = String.format("%02d", minuten)

        val dayMapping = mapOf(
            "ma" to Calendar.MONDAY,
            "di" to Calendar.TUESDAY,
            "wo" to Calendar.WEDNESDAY,
            "do" to Calendar.THURSDAY,
            "vr" to Calendar.FRIDAY,
            "za" to Calendar.SATURDAY,
            "zo" to Calendar.SUNDAY
        )

// Converteer de invoerstring naar een lijst van Calendar-dagen
        //    val weekendString = WeekendDagen
        val weekendDays = WeekendDagen.split(",")
            .mapNotNull { dayMapping[it.trim()] } // Map afkortingen naar Calendar-waarden en filter null-waarden

// Wijs de lijst toe aan profile.WeekendDays
        val calendarInstance = Calendar.getInstance() // Nieuwe tijd ophalen
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        val weekend = dayOfWeek in weekendDays


        val (OchtendStartUur, OchtendStartMinuut) = if (weekend) {
            OchtendStartWeekend.split(":").map { it.toInt() }
        } else {
            OchtendStart.split(":").map { it.toInt() }
        }

        val (NachtStartUur, NachtStartMinuut) = NachtStart.split(":").map { it.toInt() }

        if (isInTijdBereik(uurVanDag, minuten, NachtStartUur, NachtStartMinuut, OchtendStartUur, OchtendStartMinuut)) {
            return true
        } else {
            return false
        }

    }


    fun Resistentie(): Resistentie_class {
        var log_resistentie = " ﴿ Resistentie Correctie ﴾" + "\n"
        val enableResistentie = preferences.get(BooleanKey.Resistentie)
        val MinresistentiePerc = preferences.get(IntKey.Min_resistentiePerc)
        val MaxresistentiePerc = preferences.get(IntKey.Max_resistentiePerc)
        val DagresistentiePerc = preferences.get(IntKey.Dag_resistentiePerc)
        val NachtresistentiePerc = preferences.get(IntKey.Nacht_resistentiePerc)
        val Dagenresistentie = preferences.get(IntKey.Dagen_resistentie)

        val Urenresistentie = preferences.get(DoubleKey.Uren_resistentie)
        val Dagresistentie_target = preferences.get(DoubleKey.Dag_resistentie_target)
        val Nachtresistentie_target = preferences.get(DoubleKey.Nacht_resistentie_target)


        if (!enableResistentie) {
            log_resistentie = log_resistentie + " → resistentie aan/uit: uit " + "\n"
            return Resistentie_class(1.0,log_resistentie)
        }
        log_resistentie = log_resistentie + " → resistentie aan/uit: aan " + "\n"

        var ResistentieCfEff = 0.0
        var resistentie_percentage = 100
        var target = 5.2
        val (uurVanDag,minuten) = refreshTime()


        // Dag - Nacht

        val minuutTxt = String.format("%02d", minuten)



        if (Nacht()) {
            resistentie_percentage = NachtresistentiePerc
            target = Nachtresistentie_target
            log_resistentie = log_resistentie + " ● Tijd: " + uurVanDag.toString() + ":" + minuutTxt + " → s'Nachts" + "\n"
            log_resistentie = log_resistentie + "      → perc.: " + resistentie_percentage + "%" + "\n"
            log_resistentie = log_resistentie + "      → Target: " + round(target,1) + " mmol/l" + "\n"
        } else {
            resistentie_percentage = DagresistentiePerc
            target = Dagresistentie_target
            log_resistentie += " ● Tijd: " + uurVanDag.toString() + ":" + minuutTxt + " → Overdag"+ "\n"
            log_resistentie += "      → perc.: " + resistentie_percentage + "%" + "\n"
            log_resistentie += "      → Target: " + round(target,1) + " mmol/l" + "\n"
        }

        val urenTot = (uurVanDag + 1 + Urenresistentie)
        var urenTotUur = urenTot.toInt() // Uren als geheel getal
        var urenTotMinuut = ((urenTot - urenTotUur) * 60).toInt() + minuten // Minuten optellen

// Controleer of minuten >= 60 en pas aan
        if (urenTotMinuut >= 60) {
            urenTotMinuut -= 60
            urenTotUur += 1
        }

        log_resistentie += " ● Referentie periode :" + "\n"
        log_resistentie += "      → afgelopen " + Dagenresistentie.toString() + " dagen" + "\n"
        log_resistentie += "      → van ${uurVanDag + 1}:$minuutTxt tot $urenTotUur:${String.format("%02d", urenTotMinuut)}\n"


        val macht =  Math.pow(resistentie_percentage.toDouble(), 1.4)/2800
        val numPairs = Dagenresistentie // Hier kies je hoeveel paren je wilt gebruiken

        val x = Urenresistentie
        val intervals = mutableListOf<Pair<Double, Double>>()


        for (i in 1..numPairs) {
            val base = (24.0 * i) - 1    // Verhoogt telkens met 24: 24, 48, 72, ...
            intervals.add(Pair(base, base - x))
        }

        val correctionFactors = mutableListOf<Double>()
        val formatter = DateTimeFormatter.ofPattern("dd-MM")
        val today = LocalDate.now()

        for ((index, interval) in intervals.take(numPairs).withIndex()) {

            val startTime = interval.first.toLong()
            val endTime = interval.second.toLong()

            val (bgGem, bgStdDev) = logBgHistoryWithStdDev(startTime, endTime, x.toLong())
            val rel_std = (bgStdDev / bgGem * 100).toInt()
            val cf = calculateCorrectionFactor(bgGem, target, macht, rel_std)

            val dateString = today.minusDays(index.toLong()).format(formatter)

            log_resistentie += " → ${dateString} : correctie percentage = " + (cf * 100).toInt() + "%" + "\n"
            log_resistentie += "   ϟ Bg gem: ${round(bgGem, 1)}     ϟ Rel StdDev: $rel_std %.\n"

            correctionFactors.add(cf)
        }
// Bereken CfEff met het gekozen aantal correctiefactoren
        var tot_gew_gem = 0
        for (i in 0 until numPairs) {
            val divisor = when (i) {
                0   -> 70
                1   -> 25
                2   -> 5
                3   -> 3
                4   -> 2
                else -> 1 // Aanpassen voor extra correctiefactoren indien nodig
            }
            ResistentieCfEff += correctionFactors[i] * divisor
            tot_gew_gem += divisor
        }

        ResistentieCfEff = ResistentieCfEff / tot_gew_gem.toDouble()

        val minRes = MinresistentiePerc.toDouble()/100
        val maxRes = MaxresistentiePerc.toDouble()/100

        ResistentieCfEff = ResistentieCfEff.coerceIn(minRes, maxRes)

        if (ResistentieCfEff > minRes && ResistentieCfEff < maxRes){
            log_resistentie = log_resistentie + "\n" + " »» Cf_eff = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"
        } else {
            log_resistentie = log_resistentie + "\n" + " »» Cf_eff (begrensd) = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"
        }

        val resistentie_perc = (ResistentieCfEff*100).toInt().toString()
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
        val Resitenstiefile = File(externalDir, "ANALYSE/resistentie.txt")
        Resitenstiefile.writeText(resistentie_perc)

        return Resistentie_class(ResistentieCfEff,log_resistentie)

    }

    fun Stappen(): Stappen_class {

        var log_Stappen = " ﴿ Stappen ﴾" + "\n"
        var stap_perc = 100f
        var stap_target = 0f

        if (!preferences.get(BooleanKey.stappenAanUit)) {
            log_Stappen += " → resistentie aan/uit: uit " + "\n"
            return Stappen_class(stap_perc,stap_target,log_Stappen)
        }

        val now = System.currentTimeMillis()
        val timeMillis5 = now - 5 * 60 * 1000 // 5 minutes en millisecondes
        val timeMillis30 = now - 30 * 60 * 1000 // 30 minutes en millisecondes
        val timeMillis180 = now - 180 * 60 * 1000 // 180 minutes en millisecondes

        val allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(timeMillis180, now)

        var recentSteps5Minutes = 1
        var recentSteps30Minutes = 1

        if (preferences.get(BooleanKey.stappenAanUit)) {
            allStepsCounts.forEach { stepCount ->
                val timestamp = stepCount.timestamp
                if (timestamp >= timeMillis5) {
                    recentSteps5Minutes = stepCount.steps5min
                }
                if (timestamp >= timeMillis30) {
                    recentSteps30Minutes = stepCount.steps30min
                }
            }
        }

        val min5Stap = preferences.get(IntKey.stap_5minuten)
        val min30Stap = ((min5Stap * 30 / 5)/1.6).toInt()


// Variabelen om de actieve duur en huidige status bij te houden
        val thresholds = mapOf(
            " 5 minuten" to min5Stap,
            "30 minuten" to min30Stap  //,

        )
        var allThresholdsMet = true

        // Controleer de drempels
        thresholds.forEach { (label, threshold) ->
            val steps = when (label) {
                " 5 minuten" -> recentSteps5Minutes
                "30 minuten" -> recentSteps30Minutes

                else -> 0
            }
            log_Stappen += " ● $label: $steps stappen ${if (steps >= threshold) ">= drempel ($threshold)" else "< drempel ($threshold)"}\n"
            if (steps < threshold) allThresholdsMet = false
        }

        if (allThresholdsMet) {
            StapRetentie = (StapRetentie + 1).coerceAtMost(preferences.get(IntKey.stap_retentie)) // Limiteer
            log_Stappen += " ↗ Drempel overschreden. ($StapRetentie maal).\n"
        } else {
            log_Stappen += " → Drempel niet overschreden.\n"
            if (StapRetentie > 0) {
                StapRetentie = StapRetentie -1

            } // Verlaag actieve duur als deze nog actief is
        }

        // Verhoog target
        if (StapRetentie > 0) {
            if (allThresholdsMet) {
                stap_perc = preferences.get(IntKey.stap_activiteteitPerc).toFloat()
                log_Stappen += " ● Overschrijding drempels → Insuline perc. $stap_perc %.\n"
                stap_target = 36f
            } else {
                stap_perc = preferences.get(IntKey.stap_activiteteitPerc).toFloat()
                log_Stappen += " ● nog $StapRetentie * retentie → Insuline perc. $stap_perc %.\n"
                stap_target = 36f
            }
        } else {
            stap_perc = 100f
            log_Stappen += " ● Geen activiteit → Insuline perc. $stap_perc %.\n"
        }

        //   val display_Stap_perc = stap_perc.toInt()


        return Stappen_class(stap_perc,stap_target,log_Stappen)

    }

    fun Calc_Cf_IOB(IOB: Double, min_Cf: Double, max_Cf: Double, offset: Double, slope: Double, delta15: Double): Double {
        val halfSlope = slope / 2
        val adjustedIOB = (IOB + halfSlope) / (offset + halfSlope)
        val cf_iob = min_Cf + (max_Cf - min_Cf) / (1 + Math.pow(adjustedIOB, slope))
        val cf_delta = (1 + (2 * delta15) / 75).coerceIn(0.5,1.0)
        return cf_iob * cf_delta
    }
    fun Calc_Cf_Bg(Bg: Double, min_Cf: Double, max_Cf: Double, offset: Double, slope: Double): Double {

        val Cf_UAMBoost_Bg = max_Cf + (min_Cf - max_Cf)/(1 + Math.pow((Bg / offset) , slope))
        return Cf_UAMBoost_Bg
    }

    fun UAM_Boost(target_bg: Double, iob: Double): UAMBoost_class {
        val now = dateUtil.now()
        val startTime = now - T.mins(50).msecs()
        val endTime = now
        val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)

        if (bgReadings.size < 5) {
            return UAMBoost_class(100.0, "\n﴿ UAM boost ﴾\n ● Niet genoeg BG-gegevens voor berekening\n")
        }

        val delta15 = (bgReadings[0].value - bgReadings[3].value)
        val delta15_oud = (bgReadings[1].value - bgReadings[4].value)
        val bg_act = round(bgReadings[0].value / 18, 2)

        val Cf_UAMBoost_Bg = Calc_Cf_Bg(bg_act, 0.9, 1.1, (target_bg / 18 + 1), 7.0)
        val Cf_IOB = Calc_Cf_IOB(iob, 0.6, 1.2, 2.0, 6.0, delta15)

        var uam_boost_percentage = preferences.get(IntKey.uam_boostPerc).toDouble()
        var max_uam_boost_percentage = preferences.get(IntKey.max_uam_boostPerc).toDouble()
        val drempel_uam = preferences.get(DoubleKey.uam_boostDrempel) * 18.0 // 1.0 mmol/L * 18
        var display_UAM_Perc: Int
        var log = "\n﴿ UAM boost ﴾\n"
        var opm = ""

     if (delta15 < 0) {
         uam_boost_percentage = 100.0 + (delta15 * 2.5).coerceIn(-30.0, 0.0)
         display_UAM_Perc = uam_boost_percentage.toInt()
         log += " ● ∆15: ${round(delta15 / 18, 2)} (< 0) → perc. $display_UAM_Perc% \n"
         opm = "d15 < 0"
         log_uam( bg_act, iob, delta15, delta15_oud, display_UAM_Perc, opm)

         return UAMBoost_class(uam_boost_percentage, log)
         }

        // Cooldown
     val Cooldown = preferences.get(IntKey.uam_boostWachttijd).toLong()
     val minLastBoost =  ((now - lastUAMBoostTime)/(1000*60)).toInt()
     if ((now - lastUAMBoostTime) < T.mins(Cooldown).msecs()) {
         log += " ● Last Boost $minLastBoost min geleden.\n  ● cooldown actief → Geen nieuwe boost\n"
         opm = "cooldown actief"
         log_uam( bg_act, iob, delta15, delta15_oud, 100, opm)
         return UAMBoost_class(100.0, log)
        }

     if (delta15 >= drempel_uam) {
            val rest_uam = delta15 - drempel_uam
            val extra = 10 * Math.pow((delta15 - delta15_oud).toDouble(), 1.0 / 3.0)
            uam_boost_percentage += rest_uam.toInt() + extra.toInt()
            uam_boost_percentage *= Cf_UAMBoost_Bg
            uam_boost_percentage *= Cf_IOB
            uam_boost_percentage = (uam_boost_percentage).coerceIn(100.0, max_uam_boost_percentage)
            display_UAM_Perc = uam_boost_percentage.toInt()
            if (uam_boost_percentage > 125) {lastUAMBoostTime = now}  // <-- cooldown activeren
            opm = "d15 > drempel"
            log += " ● UAM-Boost perc = $display_UAM_Perc%\n"
            log += " ● ∆15 = ${round(delta15 / 18, 2)} >= ${round(drempel_uam / 18, 2)}\n"
            log += " ● Bg correctie = ${round(Cf_UAMBoost_Bg, 2)}\n"
            log += " ● IOB correctie = ${round(Cf_IOB, 2)}\n"



        } else {
            uam_boost_percentage = 100.0
            display_UAM_Perc = uam_boost_percentage.toInt()
            opm = "0 < d15 < drempel"
            log += " ● ∆15: ${round(delta15 / 18, 2)} (< drempel) → Geen boost\n"

        }
        log_uam(bg_act, iob, delta15, delta15_oud, display_UAM_Perc, opm)
        return UAMBoost_class(uam_boost_percentage, log)
    }

    fun Persistent(): Persistent_class {
        var log_Persistent = " ﴿ Persistent hoog ﴾" + "\n"
        var Persistent_ISF_cf = 1.0
        if (!preferences.get(BooleanKey.PersistentAanUit)) {
            log_Persistent += " → persistent uitgeschakeld " + "\n"
            return Persistent_class(Persistent_ISF_cf,log_Persistent)
        }


        val startTime = dateUtil.now() -  T.mins(min = 50).msecs()     //T.hours(hour = 1).msecs()
        val endTime = dateUtil.now()
        val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)
        var delta15 = 0.0
        //    var delta15_oud = 0.0
        var bg_act = round(bgReadings[0].value/18,2)
        var delta5 = 0f
        var delta30 = 0f
        if (bgReadings.size >= 7) {
            delta15 = (bgReadings[0].value - bgReadings[3].value)
            //       delta15_oud = (bgReadings[1].value - bgReadings[4].value)    carb_time
            bg_act = round(bgReadings[0].value/18,2)
            delta5 = (bgReadings[0].value - bgReadings[1].value).toFloat()
            delta30 = (bgReadings[0].value - bgReadings[6].value).toFloat()
        }

        var Persistent_ISF_perc: Double
        val Display_Persistent_perc: Int
        val Persistent_Drempel: Double
        val extraNachtrange: Double
        val Max_Persistent_perc: Int

        val DeelvanDag: String
        if (!Nacht()) {
            Persistent_Drempel = preferences.get(DoubleKey.persistent_Dagdrempel)
            Max_Persistent_perc = preferences.get(IntKey.Dag_MaxPersistentPerc)
            extraNachtrange = 0.0
            DeelvanDag = "overdag"
        } else {
            Persistent_Drempel = preferences.get(DoubleKey.persistent_Nachtdrempel)
            Max_Persistent_perc = preferences.get(IntKey.Nacht_MaxPersistentPerc)
            extraNachtrange = 0.5
            DeelvanDag = "'s nachts"
        }


        val Pers_grensL = (preferences.get(DoubleKey.persistent_grens) * 18) - extraNachtrange
        val Pers_grensH = (preferences.get(DoubleKey.persistent_grens) * 18) + 2 + extraNachtrange

        if (delta5>-Pers_grensL && delta5<Pers_grensH && delta15>-Pers_grensL-2 && delta15<Pers_grensH+2 && delta30>-Pers_grensL-4 && delta30<Pers_grensH+4 && bg_act > Persistent_Drempel) {
            Persistent_ISF_perc = (((bg_act - Persistent_Drempel ) / 10.0) + 1.0) * 100 * 1.05
            Persistent_ISF_perc = Persistent_ISF_perc.coerceIn(100.0, Max_Persistent_perc.toDouble())
            Display_Persistent_perc = Persistent_ISF_perc.toInt()
            log_Persistent += " → Persistent hoge Bg gedetecteerd" + "\n"
            log_Persistent +=  " ● " + DeelvanDag + " → Drempel = " + round(Persistent_Drempel,1) + "\n"
            log_Persistent +=  " ● Bg= " + round(bg_act, 1) + " → Insuline perc = " + Display_Persistent_perc + "%" + "\n"


        } else {
            Persistent_ISF_perc = 100.0
            log_Persistent +=  " ● geen Persistent hoge Bg gedetecteerd" + "\n"
            log_Persistent +=  " ● " + DeelvanDag + " → Drempel = " + round(Persistent_Drempel,1) + "\n"

        }
        Persistent_ISF_cf = Persistent_ISF_perc /100
        return Persistent_class(Persistent_ISF_cf,log_Persistent)

    }

    fun ActExtraIns(): Extra_Insuline {

        val tijdNu = System.currentTimeMillis()/(60 * 1000)
        var extra_insuline_tijdstip = "0"
        var extra_insuline_tijd = "0"
        var extra_insuline_percentage = "0"
        val extra_insuline: Boolean
        val corr_factor: Float
        val Cf_overall: Float
        val Cf_tijd : Double
        var log_ExtraIns : String

        var extra_insuline_check = "0"

        try {
            val sc = Scanner(ActExtraIns)
            var teller = 1
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                if (teller == 1) { extra_insuline_check = line}
                if (teller == 2) { extra_insuline_tijdstip = line}
                if (teller == 3) { extra_insuline_tijd = line}
                if (teller == 4) { extra_insuline_percentage = line}

                teller += 1
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val verstreken_tijd = (tijdNu - extra_insuline_tijdstip.toFloat()).toInt()
        var resterendeTijd = extra_insuline_tijd.toInt() - verstreken_tijd
        var cf:Double

        if (verstreken_tijd < extra_insuline_tijd.toInt() && extra_insuline_check == "checked") {
            extra_insuline = true
            corr_factor = (extra_insuline_percentage.toFloat()/100)

            val Max_Cf_tijd = 1.0 + 0.2
            val Min_Cf_tijd = 1.0 - 0.2
            val Offset_tijd = extra_insuline_tijd.toFloat()/2 + 10
            val Slope_tijd = 3
            Cf_tijd = Min_Cf_tijd + (Max_Cf_tijd - Min_Cf_tijd)/(1 + Math.pow((verstreken_tijd.toDouble() / Offset_tijd) , Slope_tijd.toDouble()))

            Cf_overall = round(corr_factor * Cf_tijd,2).toFloat()
            cf = Cf_overall.toDouble()

            val info_cf = (cf * 100).toInt()
            log_ExtraIns = " \n" + " ﴿ Extra bolus insuline  ―――――﴾" + "\n"
            log_ExtraIns +=  " → Nog $resterendeTijd minuten resterend" + "\n"
            log_ExtraIns +=  " ● Opgegeven perc = " + extra_insuline_percentage + "%" + "\n"
            log_ExtraIns +=  " ● Dynamische factor = " + round(Cf_tijd,2) + "\n"
            log_ExtraIns +=  " ● Overall perc = $info_cf%" + "\n"
        } else {
            extra_insuline = false
            cf = 1.0

            log_ExtraIns = " \n" + " ﴿ Extra bolus insuline  ―――――﴾" + "\n"
            log_ExtraIns +=  " ● Niet actief " + "\n"

        }


        return Extra_Insuline(extra_insuline,cf,log_ExtraIns)
    }


    fun BolusViaSMB(): Bolus_SMB {
        val tijdNu = System.currentTimeMillis() / (60 * 1000)
        var bolus_basaal_check = "0"
        var bolus_basaal_tijdstip = "0"
        var aantal_fracties = "0"
        var insuline = "0"

        try {
            val sc = Scanner(Bolus_SMB)
            var teller = 1
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                when (teller) {
                    1 -> bolus_basaal_check = line
                    2 -> bolus_basaal_tijdstip = line
                    3 -> aantal_fracties = line
                    4 -> insuline = line
                }
                teller += 1
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val totaal_fracties = aantal_fracties.toInt()

        // Eerst controleren of dit een nieuwe bolusopdracht is
        val opgeslagenTijdstip = leesLaatsteFractie().first
        if (bolus_basaal_tijdstip.toInt() != opgeslagenTijdstip) {
            schrijfLaatsteFractie(bolus_basaal_tijdstip.toInt(), -1)
        }

        // Pas hierna lezen van teller/fractie
        val (herladenTijdstip, laatstFractie) = leesLaatsteFractie()
        val huidigeFractie = laatstFractie + 1
        val rest_fracties = totaal_fracties - huidigeFractie

        val fractie_duur = 5
        val verstreken_minuten = tijdNu.toInt() - bolus_basaal_tijdstip.toInt()
        val theoretische_fractie = verstreken_minuten / fractie_duur

        val fractie_aan_de_beurt = (
            (huidigeFractie == 0 && verstreken_minuten >= 0) ||
                (huidigeFractie > 0 && huidigeFractie <= theoretische_fractie)
            )

        return if (
            bolus_basaal_check == "checked" &&
            huidigeFractie < totaal_fracties &&
            fractie_aan_de_beurt
        ) {
            schrijfLaatsteFractie(bolus_basaal_tijdstip.toInt(), huidigeFractie)
            val Extra_smb = insuline.toFloat() / totaal_fracties
            Bolus_SMB(true, Extra_smb, rest_fracties - 1)
        } else {
            Bolus_SMB(false, 0.0f, rest_fracties)
        }
    }



    fun leesLaatsteFractie(): Pair<Int, Int> {
        return try {
            if (LaatsteSMBFractie.exists()) {
                val regels = LaatsteSMBFractie.readLines()
                if (regels.size >= 2) {
                    val opgeslagenTijdstip = regels[0].toInt()
                    val laatsteFractie = regels[1].toInt()
                    Pair(opgeslagenTijdstip, laatsteFractie)
                } else {
                    Pair(-1, -1)
                }
            } else {
                Pair(-1, -1)
            }
        } catch (e: Exception) {
            Pair(-1, -1)
        }
    }

    fun schrijfLaatsteFractie(tijdstip: Int, fractie: Int) {
        try {
            LaatsteSMBFractie.writeText("$tijdstip\n$fractie")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }




    fun getCurrentWeekId(): String {
        val now = java.util.Calendar.getInstance()
        val week = now.get(java.util.Calendar.WEEK_OF_YEAR)
        val year = now.get(java.util.Calendar.YEAR)
        return String.format("%d-W%02d", year, week)
    }

    fun log_uam(Bg: Double, IOB: Double, D15: Double, D15_oud: Double, perc: Int, Opm: String) {
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).toString()
        val BgStr = round(Bg, 1).toString()
        val iobStr = round(IOB, 2).toString()
        val delta15Str = round(D15 / 18, 1).toString()
        val delta15oudStr = round(D15_oud / 18, 1).toString()
        val UAMPercStr = perc.toString()

        val headerRow = "datum, bg, iob, delta15, delta15oud, Bgperc, opm.\n"
        val valuesToRecord = "$dateStr, $BgStr, $iobStr, $delta15Str, $delta15oudStr, $UAMPercStr, $Opm"

        // Bepaal pad op basis van weeknummer
        val weekId = getCurrentWeekId()
        val weeklyLogFile = File(externalDir, "ANALYSE/weekly/uam_log_$weekId.csv")

        // Zorg dat de map bestaat
        if (!weeklyLogFile.parentFile.exists()) {
            weeklyLogFile.parentFile.mkdirs()
        }

        // Bestand aanmaken met header als het nog niet bestaat
        if (!weeklyLogFile.exists()) {
            weeklyLogFile.createNewFile()
            weeklyLogFile.appendText(headerRow)
        }

        // Lees bestaande regels
        val existingLines = weeklyLogFile.readLines()
        val updatedContent = buildString {
            appendLine(headerRow.trim())
            appendLine(valuesToRecord)
            for (i in 1 until existingLines.size) {
                appendLine(existingLines[i])
            }
        }

        // Overschrijf bestand
        weeklyLogFile.writeText(updatedContent)
    }



    fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfile, autosens_data: AutosensResult, meal_data: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )

        val (bolus_SMB_AanUit,ExtraSMB, rest_aantalSMB) = BolusViaSMB()
        val (extraIns_AanUit,extraIns_Factor,log_ExtraIns) = ActExtraIns()



        // TODO eliminate
        val deliverAt = currentTime




        // TODO eliminate
        val profile_current_basal = round_basal(profile.current_basal)
        var basal = profile_current_basal

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        val bg = glucose_status.glucose
        // TODO eliminate
        val noise = glucose_status.noise
        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }
        if (bg <= 10 || bg == 38.0 || noise >= 3 || minAgo > 12 || minAgo < -5 || (bg > 60 && flatBGsDetected)) {
            if (currenttemp.rate > basal) { // high temp is running
                rT.reason.append(". Replacing high temp basal of ${currenttemp.rate} with neutral temp of $basal")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = basal
                return rT
            } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) { //shorten long zero temps to 30m
                rT.reason.append(". Shortening " + currenttemp.duration + "m long zero temp to 30m. ")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = 0.0
                return rT
            } else { //do nothing.
                rT.reason.append(". Temp ${currenttemp.rate} <= current basal ${round(basal, 2)}U/hr; doing nothing. ")
                return rT
            }
        }

        // TODO eliminate
        val max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver

        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg


        var sensitivityRatio: Double
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = 100 // evaluate high/low temptarget against 100, not scheduled target (which might change)
        // when temptarget is 160 mg/dL, run 50% basal (120 = 75%; 140 = 60%),  80 mg/dL with low_temptarget_lowers_sensitivity would give 1.5x basal, but is limited to autosens_max (1.2x by default)
        val halfBasalTarget = profile.half_basal_exercise_target

        if (dynIsfMode) {
            consoleError("---------------------------------------------------------")
            consoleError(" Dynamic ISF version 2.0 ")
            consoleError("---------------------------------------------------------")
        }

        val iobArray = iob_data_array
        val iob_data = iobArray[0]

        var log_uam = ""
        var UAM_boost_perc = 100.0
        if (preferences.get(BooleanKey.uamBoost)) {
            if (!Nacht()) {
                var (uam_perc, log_uam) = UAM_Boost(target_bg, iob_data.iob)
                UAM_boost_perc = uam_perc
                consoleLog(log_uam)
            } else {
                log_uam = "\n﴿ UAM boost ﴾\n ● Nacht: Boost niet actief\n"
            }
        } else {
            log_uam = "\n﴿ UAM boost ﴾\n ● Boost uitgeschakeld\n"
        }


        val (resistentie_factor,log_res) = Resistentie()
        val (persistent_factor,log_persistent) = Persistent()
        val (stap_perc,stap_target,log_stappen) = Stappen()


        val pomp = activePlugin.activePump.pumpDescription.tempBasalStyle.toString()  //1 bij procent en 2 bij absoluut
        consoleLog(log_res)
        consoleLog(log_uam)
        consoleLog(log_persistent)
        consoleLog(log_stappen)
        consoleLog(log_ExtraIns)


        sensitivityRatio = resistentie_factor

     //   var sens_factor = 1.0
     //   var basaal_factor = 1.0
        var cf_factor_overall = 1.0

        if (preferences.get(BooleanKey.Resistentie)) {
     //       sens_factor *= resistentie_factor
     //       basaal_factor *= resistentie_factor
            cf_factor_overall *= resistentie_factor
               }
        if (extraIns_AanUit) {
    //        sens_factor *= extraIns_Factor
    //        basaal_factor *= extraIns_Factor
            cf_factor_overall *= extraIns_Factor
               }
        if (preferences.get(BooleanKey.stappenAanUit)) {
    //        sens_factor *= (stap_perc/100)
    //        basaal_factor *= (stap_perc/100)
            cf_factor_overall *= (stap_perc/100)
              }
        if (preferences.get(BooleanKey.PersistentAanUit)) {
    //        sens_factor *= persistent_factor
    //        basaal_factor *= persistent_factor
            cf_factor_overall *= persistent_factor
            }
        if (preferences.get(BooleanKey.uamBoost)) {
    //        sens_factor *= (UAM_boost_perc/100)
    //        basaal_factor *= (UAM_boost_perc/100)
            cf_factor_overall *= (UAM_boost_perc/100)
            }



        basal = profile.current_basal * cf_factor_overall // sensitivityRatio
        basal = round_basal(basal)
        val txtProfileBasal = round(profile_current_basal,2)
        val txtBasal = round(basal,2)



        if (profile.temptargetSet) {
            //console.log("Temp Target set, not adjusting with autosens; ");
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleLog("target_bg unchanged: $new_target_bg; ")
                else
                    consoleLog("target_bg from $target_bg to $new_target_bg; ")

                target_bg = new_target_bg
            }
        }
        target_bg += stap_target


        val tick: String

        tick = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))


        var sens = profile.sens / cf_factor_overall

        consoleLog("Correctie overall =  " + (cf_factor_overall*100).toInt() + "%")
        consoleLog("ISF van " + round(profile.sens/18,1) + " naar " + round(sens/18,1))
        consoleLog("Basaal van $txtProfileBasal naar $txtBasal \n")



        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }

        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG =
            if (dynIsfMode)
                round(bg - (iob_data.iob * sens), 0)
            else {
                if (iob_data.iob > 0) round(bg - (iob_data.iob * sens), 0)
                else  // if IOB is negative, be more conservative and use the lower of sens, profile.sens
                    round(bg - (iob_data.iob * min(sens, profile.sens)), 0)
            }
        // and adjust it for the deviation above
        var eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it
            //console.error("naive_eventualBG:",naive_eventualBG+", eventualBG:",eventualBG);
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleLog("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleLog("min_bg unchanged: $min_bg; ")
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleLog("target_bg from $target_bg to $adjustedTargetBG; ")
                target_bg = adjustedTargetBG
            } else {
                consoleLog("target_bg unchanged: $target_bg; ")
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError("max_bg unchanged: $max_bg")
            }
        }

        val expectedDelta = calculate_expected_delta(target_bg, eventualBG, bgi)

        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        var threshold = min_bg - 0.5 * (min_bg - 40)
        if (profile.lgsThreshold != null) {
            val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
            if (lgsThreshold > threshold) {
                consoleError("Threshold set from ${convert_bg(threshold)} to ${convert_bg(lgsThreshold.toDouble())}; ")
                threshold = lgsThreshold.toDouble()
            }
        }

        //console.error(reservoir_data);

        rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = sens  //profile.variable_sens
        )

        // TSUNAMI CALCULATION:
        val tsunamiResult = determineTsunamiInsReq(glucose_status, target_bg, sens, profile, iob_data, currentTime)
        rT.reason.append(tsunamiResult.reason)

        // generate predicted future BGs based on IOB, COB, and current absorption rate

        var COBpredBGs = mutableListOf<Double>()
        var aCOBpredBGs = mutableListOf<Double>()
        var IOBpredBGs = mutableListOf<Double>()
        var UAMpredBGs = mutableListOf<Double>()
        var ZTpredBGs = mutableListOf<Double>()
        COBpredBGs.add(bg)
        aCOBpredBGs.add(bg)
        IOBpredBGs.add(bg)
        ZTpredBGs.add(bg)
        UAMpredBGs.add(bg)

        var enableSMB = enable_smb(profile, microBolusAllowed, meal_data, target_bg)

        // enable UAM (if enabled in preferences)
        val enableUAM = profile.enableUAM

        //console.error(meal_data);
        // carb impact and duration are 0 unless changed below
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        val uci = round((minDelta - bgi), 1)
        // ISF (mg/dL/U) / CR (g/U) = CSF (mg/dL/g)

        // TODO: remove commented-out code for old behavior

        val csf = sens / profile.carb_ratio
        consoleError("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            consoleError("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI
        }
        var remainingCATimeMin = 3.0 // h; duration of expected not-yet-observed carb absorption

        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        // 20 g/h means that anything <= 60g will get a remainingCATimeMin, 80g will get 4h, and 120g 6h
        // when actual absorption ramps up it will take over from remainingCATime
        val assumedCarbAbsorptionRate = 20 // g/h; maximum rate to assume carbs will absorb if no CI observed
        var remainingCATime = remainingCATimeMin
        if (meal_data.carbs != 0.0) {
            // if carbs * assumedCarbAbsorptionRate > remainingCATimeMin, raise it
            // so <= 90g is assumed to take 3h, and 120g=4h
            remainingCATimeMin = Math.max(remainingCATimeMin, meal_data.mealCOB / assumedCarbAbsorptionRate)
            val lastCarbAge = round((systemTime - meal_data.lastCarbTime) / 60000.0)
            //console.error(meal_data.lastCarbTime, lastCarbAge);

            val fractionCOBAbsorbed = (meal_data.carbs - meal_data.mealCOB) / meal_data.carbs
            remainingCATime = remainingCATimeMin + 1.5 * lastCarbAge / 60
            remainingCATime = round(remainingCATime, 1)
            //console.error(fractionCOBAbsorbed, remainingCATimeAdjustment, remainingCATime)
            consoleError("Last carbs " + lastCarbAge + "minutes ago; remainingCATime:" + remainingCATime + "hours;" + round(fractionCOBAbsorbed * 100) + "% carbs absorbed")
        }

        // calculate the number of carbs absorbed over remainingCATime hours at current CI
        // CI (mg/dL/5m) * (5m)/5 (m) * 60 (min/hr) * 4 (h) / 2 (linear decay factor) = total carb impact (mg/dL)
        val totalCI = Math.max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, meal_data.mealCOB - totalCA)
        remainingCarbs = Math.min(remainingCarbsCap.toDouble(), remainingCarbs)
        // assume remainingCarbs will absorb in a /\ shaped bilinear curve
        // peaking at remainingCATime / 2 and ending at remainingCATime hours
        // area of the /\ triangle is the same as a remainingCIpeak-height rectangle out to remainingCATime/2
        // remainingCIpeak (mg/dL/5m) = remainingCarbs (g) * CSF (mg/dL/g) * 5 (m/5m) * 1h/60m / (remainingCATime/2) (h)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        if (remainingCIpeak.isNaN()) {
            throw Exception("remainingCarbs=$remainingCarbs remainingCATime=$remainingCATime profile.remainingCarbsCap=${profile.remainingCarbsCap} csf=$csf")
        }
        //console.error(profile.min_5m_carbimpact,ci,totalCI,totalCA,remainingCarbs,remainingCI,remainingCATime);

        // calculate peak deviation in last hour, and slope from that to current deviation
        val slopeFromMaxDeviation = round(meal_data.slopeFromMaxDeviation, 2)
        // calculate lowest deviation in last hour, and slope from that to current deviation
        val slopeFromMinDeviation = round(meal_data.slopeFromMinDeviation, 2)
        // assume deviations will drop back down at least at 1/3 the rate they ramped up
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)
        //console.error(slopeFromMaxDeviation);

        val aci = 10
        //5m data points = g * (1U/10g) * (40mg/dL/1U) / (mg/dL/5m)
        // duration (in 5m data points) = COB (g) * CSF (mg/dL/g) / ci (mg/dL/5m)
        // limit cid to remainingCATime hours: the reset goes to remainingCI
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, meal_data.mealCOB * csf / ci))
        }
        val acid = max(0.0, meal_data.mealCOB * csf / aci)
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
        consoleError("Carb Impact: ${ci} mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
        //console.error("Accel. Carb Impact:",aci,"mg/dL per 5m; ACI Duration:",round(acid*5/60*2,1),"hours");
        var minIOBPredBG = 999.0
        var minCOBPredBG = 999.0
        var minUAMPredBG = 999.0
        var minGuardBG: Double
        var minCOBGuardBG = 999.0
        var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0
        var minZTGuardBG = 999.0
        var minPredBG: Double
        var avgPredBG: Double
        var IOBpredBG: Double = eventualBG
        var maxIOBPredBG = bg
        var maxCOBPredBG = bg
        //var maxUAMPredBG = bg
        //var maxPredBG = bg;
        //var eventualPredBG = bg
        val lastIOBpredBG: Double
        var lastCOBpredBG: Double? = null
        var lastUAMpredBG: Double? = null
        //var lastZTpredBG: Int
        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null
        var COBpredBG: Double? = null
        var aCOBpredBG: Double?
        iobArray.forEach { iobTick ->
            //console.error(iobTick);
            val predBGI: Double = round((-iobTick.activity * sens * 5), 2)
            val IOBpredBGI: Double =
                if (dynIsfMode) round((-iobTick.activity * (1800 / (profile.TDD * (ln((max(IOBpredBGs[IOBpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            // try to find where is crashing https://console.firebase.google.com/u/0/project/androidaps-c34f8/crashlytics/app/android:info.nightscout.androidaps/issues/950cdbaf63d545afe6d680281bb141e5?versions=3.3.0-dev-d%20(1500)&time=last-thirty-days&types=crash&sessionEventKey=673BF7DD032300013D4704707A053273_2017608123846397475
            if (iobTick.iobWithZeroTemp!!.activity.isNaN() || sens.isNaN())
                fabricPrivacy.logCustom("iobTick.iobWithZeroTemp!!.activity=${iobTick.iobWithZeroTemp!!.activity} sens=$sens")
            val predZTBGI =
                if (dynIsfMode) round((-iobTick.iobWithZeroTemp!!.activity * (1800 / (profile.TDD * (ln((max(ZTpredBGs[ZTpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else round((-iobTick.iobWithZeroTemp!!.activity * sens * 5), 2)
            val predUAMBGI =
                if (dynIsfMode) round((-iobTick.activity * (1800 / (profile.TDD * (ln((max(UAMpredBGs[UAMpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            // for IOBpredBGs, predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            // calculate predBGs with long zero temp without deviations
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            // for COBpredBGs, predicted carb impact drops linearly from current carb impact down to zero
            // eventually accounting for all carbs (if they can be absorbed over DIA)
            val predCI: Double = max(0.0, max(0.0, ci) * (1 - COBpredBGs.size / max(cid * 2, 1.0)))
            val predACI = max(0.0, max(0, aci) * (1 - COBpredBGs.size / max(acid * 2, 1.0)))
            // if any carbs aren't absorbed after remainingCATime hours, assume they'll absorb in a /\ shaped
            // bilinear curve peaking at remainingCIpeak at remainingCATime/2 hours (remainingCATime/2*12 * 5m)
            // and ending at remainingCATime h (remainingCATime*12 * 5m intervals)
            val intervals = Math.min(COBpredBGs.size.toDouble(), ((remainingCATime * 12) - COBpredBGs.size))
            val remainingCI = Math.max(0.0, intervals / (remainingCATime / 2 * 12) * remainingCIpeak)
            if (remainingCI.isNaN()) {
                throw Exception("remainingCI=$remainingCI intervals=$intervals remainingCIpeak=$remainingCIpeak")
            }
            remainingCItotal += predCI + remainingCI
            remainingCIs.add(round(remainingCI))
            predCIs.add(round(predCI))
            //console.log(round(predCI,1)+"+"+round(remainingCI,1)+" ");
            COBpredBG = COBpredBGs[COBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predCI + remainingCI
            aCOBpredBG = aCOBpredBGs[aCOBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predACI
            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            //console.error(predUCIslope, predUCImax);
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                //console.error(UAMpredBGs.length,slopeFromDeviations, predUCI);
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI
            //console.error(predBGI, predCI, predUCI);
            // truncate all BG predictions at 4 hours
            if (IOBpredBGs.size < 48) IOBpredBGs.add(IOBpredBG)
            if (COBpredBGs.size < 48) COBpredBGs.add(COBpredBG!!)
            if (aCOBpredBGs.size < 48) aCOBpredBGs.add(aCOBpredBG!!)
            if (UAMpredBGs.size < 48) UAMpredBGs.add(UAMpredBG!!)
            if (ZTpredBGs.size < 48) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (COBpredBG!! < minCOBGuardBG) minCOBGuardBG = round(COBpredBG!!).toDouble()
            if (UAMpredBG!! < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG!!).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            // set minPredBGs starting when currently-dosed insulin activity will peak
            // look ahead 60m (regardless of insulin type) so as to be less aggressive on slower insulins
            // add 30m to allow for insulin delivery (SMBs or temps)
            //MP Below peak variables have been changed from original
            val insulinPeakTime = 40
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0
            //console.error(insulinPeakTime, insulinPeak5m, profile.insulinPeakTime, profile.curve);

            // wait 90m before setting minIOBPredBG
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            // wait 85-105m before setting COB and 60m for UAM minPredBGs
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBGs.size > insulinPeak5m && (COBpredBG!! < minCOBPredBG)) minCOBPredBG = round(COBpredBG!!, 0)
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBG!! > maxIOBPredBG) maxCOBPredBG = COBpredBG!!
            if (enableUAM && UAMpredBGs.size > 12 && (UAMpredBG!! < minUAMPredBG)) minUAMPredBG = round(UAMpredBG!!, 0)
            //if (enableUAM && UAMpredBG!! > maxIOBPredBG) maxUAMPredBG = UAMpredBG!!
        }
        // set eventualBG to include effect of carbs
        //console.error("PredBGs:",JSON.stringify(predBGs));
        if (meal_data.mealCOB > 0) {
            consoleError("predCIs (mg/dL/5m):" + predCIs.joinToString(separator = " "))
            consoleError("remainingCIs:      " + remainingCIs.joinToString(separator = " "))
        }
        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            // stop displaying ZTpredBGs once they're rising and above target
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }
        if (meal_data.mealCOB > 0) {
            aCOBpredBGs = aCOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in aCOBpredBGs.size - 1 downTo 13) {
                if (aCOBpredBGs[i - 1] != aCOBpredBGs[i]) break
                else aCOBpredBGs.removeAt(aCOBpredBGs.lastIndex)
            }
        }
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            COBpredBGs = COBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in COBpredBGs.size - 1 downTo 13) {
                if (COBpredBGs[i - 1] != COBpredBGs[i]) break
                else COBpredBGs.removeAt(COBpredBGs.lastIndex)
            }
            rT.predBGs?.COB = COBpredBGs.map { it.toInt() }
            lastCOBpredBG = COBpredBGs[COBpredBGs.size - 1]
            eventualBG = max(eventualBG, round(COBpredBGs[COBpredBGs.size - 1], 0))
        }
        if (ci > 0 || remainingCIpeak > 0) {
            if (enableUAM) {
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }

            // set eventualBG based on COB or UAM predBGs
            rT.eventualBG = eventualBG
        }


        if (bolus_SMB_AanUit) {

            rT.reason.append("=> Insuline via SMB:  $ExtraSMB eh per keer. nog $rest_aantalSMB keer resterend")

            rT.deliverAt = deliverAt
            rT.duration = 30
            rT.rate = 0.0
            rT.units = ExtraSMB.toDouble()
            return rT
        }




        consoleError("UAM Impact: $uci mg/dL per 5m; UAM Duration: $UAMduration hours")
        consoleLog("EventualBG is $eventualBG ;")

        minIOBPredBG = max(39.0, minIOBPredBG)
        minCOBPredBG = max(39.0, minCOBPredBG)
        minUAMPredBG = max(39.0, minUAMPredBG)
        minPredBG = round(minIOBPredBG, 0)

        val fSensBG = min(minPredBG, bg)

        var future_sens = 0.0
        if (dynIsfMode) {
            if (bg > target_bg && glucose_status.delta < 3 && glucose_status.delta > -3 && glucose_status.shortAvgDelta > -3 && glucose_status. shortAvgDelta < 3 && eventualBG > target_bg && eventualBG
                < bg
            ) {
                future_sens = (1800 / (ln((((fSensBG * 0.5) + (bg * 0.5)) / profile.insulinDivisor) + 1) * profile.TDD))
                future_sens = round(future_sens, 1)
                consoleLog("Future state sensitivity is $future_sens based on eventual and current bg due to flat glucose level above target")
                rT.reason.append("Dosing sensitivity: $future_sens using eventual BG;")
            } else if (glucose_status.delta > 0 && eventualBG > target_bg || eventualBG > bg) {
                future_sens = (1800 / (ln((bg / profile.insulinDivisor) + 1) * profile.TDD))
                future_sens = round(future_sens, 1)
                consoleLog("Future state sensitivity is $future_sens using current bg due to small delta or variation")
                rT.reason.append("Dosing sensitivity: $future_sens using current BG;")
            } else {
                future_sens = (1800 / (ln((fSensBG / profile.insulinDivisor) + 1) * profile.TDD))
                future_sens = round(future_sens, 1)
                consoleLog("Future state sensitivity is $future_sens based on eventual bg due to -ve delta")
                rT.reason.append("Dosing sensitivity: $future_sens using eventual BG;")
            }
        }

        val fractionCarbsLeft = meal_data.mealCOB / meal_data.carbs
        // if we have COB and UAM is enabled, average both
        if (minUAMPredBG < 999 && minCOBPredBG < 999) {
            // weight COBpredBG vs. UAMpredBG based on how many carbs remain as COB
            avgPredBG = round((1 - fractionCarbsLeft) * UAMpredBG!! + fractionCarbsLeft * COBpredBG!!, 0)
            // if UAM is disabled, average IOB and COB
        } else if (minCOBPredBG < 999) {
            avgPredBG = round((IOBpredBG + COBpredBG!!) / 2.0, 0)
            // if we have UAM but no COB, average IOB and UAM
        } else if (minUAMPredBG < 999) {
            avgPredBG = round((IOBpredBG + UAMpredBG!!) / 2.0, 0)
        } else {
            avgPredBG = round(IOBpredBG, 0)
        }
        // if avgPredBG is below minZTGuardBG, bring it up to that level
        if (minZTGuardBG > avgPredBG) {
            avgPredBG = minZTGuardBG
        }

        // if we have both minCOBGuardBG and minUAMGuardBG, blend according to fractionCarbsLeft
        if ((cid > 0.0 || remainingCIpeak > 0)) {
            if (enableUAM) {
                minGuardBG = fractionCarbsLeft * minCOBGuardBG + (1 - fractionCarbsLeft) * minUAMGuardBG
            } else {
                minGuardBG = minCOBGuardBG
            }
        } else if (enableUAM) {
            minGuardBG = minUAMGuardBG
        } else {
            minGuardBG = minIOBGuardBG
        }
        minGuardBG = round(minGuardBG, 0)
        //console.error(minCOBGuardBG, minUAMGuardBG, minIOBGuardBG, minGuardBG);

        var minZTUAMPredBG = minUAMPredBG
        // if minZTGuardBG is below threshold, bring down any super-high minUAMPredBG by averaging
        // this helps prevent UAM from giving too much insulin in case absorption falls off suddenly
        if (minZTGuardBG < threshold) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
            // if minZTGuardBG is between threshold and target, blend in the averaging
        } else if (minZTGuardBG < target_bg) {
            // target 100, threshold 70, minZTGuardBG 85 gives 50%: (85-70) / (100-70)
            val blendPct = (minZTGuardBG - threshold) / (target_bg - threshold)
            val blendedMinZTGuardBG = minUAMPredBG * blendPct + minZTGuardBG * (1 - blendPct)
            minZTUAMPredBG = (minUAMPredBG + blendedMinZTGuardBG) / 2.0
            //minZTUAMPredBG = minUAMPredBG - target_bg + minZTGuardBG;
            // if minUAMPredBG is below minZTGuardBG, bring minUAMPredBG up by averaging
            // this allows more insulin if lastUAMPredBG is below target, but minZTGuardBG is still high
        } else if (minZTGuardBG > minUAMPredBG) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        }
        minZTUAMPredBG = round(minZTUAMPredBG, 0)
        //console.error("minUAMPredBG:",minUAMPredBG,"minZTGuardBG:",minZTGuardBG,"minZTUAMPredBG:",minZTUAMPredBG);
        // if any carbs have been entered recently
        if (meal_data.carbs != 0.0) {

            // if UAM is disabled, use max of minIOBPredBG, minCOBPredBG
            if (!enableUAM && minCOBPredBG < 999) {
                minPredBG = round(max(minIOBPredBG, minCOBPredBG), 0)
                // if we have COB, use minCOBPredBG, or blendedMinPredBG if it's higher
            } else if (minCOBPredBG < 999) {
                // calculate blendedMinPredBG based on how many carbs remain as COB
                val blendedMinPredBG = fractionCarbsLeft * minCOBPredBG + (1 - fractionCarbsLeft) * minZTUAMPredBG
                // if blendedMinPredBG > minCOBPredBG, use that instead
                minPredBG = round(max(minIOBPredBG, max(minCOBPredBG, blendedMinPredBG)), 0)
                // if carbs have been entered, but have expired, use minUAMPredBG
            } else if (enableUAM) {
                minPredBG = minZTUAMPredBG
            } else {
                minPredBG = minGuardBG
            }
            // in pure UAM mode, use the higher of minIOBPredBG,minUAMPredBG
        } else if (enableUAM) {
            minPredBG = round(max(minIOBPredBG, minZTUAMPredBG), 0)
        }
        // make sure minPredBG isn't higher than avgPredBG
        minPredBG = min(minPredBG, avgPredBG)

        consoleLog("minPredBG: $minPredBG minIOBPredBG: $minIOBPredBG minZTGuardBG: $minZTGuardBG")
        if (minCOBPredBG < 999) {
            consoleLog(" minCOBPredBG: $minCOBPredBG")
        }
        if (minUAMPredBG < 999) {
            consoleLog(" minUAMPredBG: $minUAMPredBG")
        }
        consoleError(" avgPredBG: $avgPredBG COB: ${meal_data.mealCOB} / ${meal_data.carbs}")
        // But if the COB line falls off a cliff, don't trust UAM too much:
        // use maxCOBPredBG if it's been set and lower than minPredBG
        if (maxCOBPredBG > bg) {
            minPredBG = min(minPredBG, maxCOBPredBG)
        }

        rT.COB = meal_data.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg(deviation.toDouble())}, BGI: ${convert_bg(bgi)}, ISF: ${convert_bg(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convert_bg(target_bg)}, minPredBG ${convert_bg(minPredBG)}, minGuardBG ${convert_bg(minGuardBG)}, IOBpredBG ${convert_bg(lastIOBpredBG)}"
        )
        if (lastCOBpredBG != null) {
            rT.reason.append(", COBpredBG " + convert_bg(lastCOBpredBG.toDouble()))
        }
        if (lastUAMpredBG != null) {
            rT.reason.append(", UAMpredBG " + convert_bg(lastUAMpredBG.toDouble()))
        }
        rT.reason.append("; ")
        // use naive_eventualBG if above 40, but switch to minGuardBG if both eventualBGs hit floor of 39
        var carbsReqBG = naive_eventualBG
        if (carbsReqBG < 40) {
            carbsReqBG = min(minGuardBG, carbsReqBG)
        }
        var bgUndershoot: Double = threshold - carbsReqBG
        // calculate how long until COB (or IOB) predBGs drop below min_bg
        var minutesAboveMinBG = 240
        var minutesAboveThreshold = 240
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], min_bg);
                if (COBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], threshold);
                if (COBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        } else {
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], min_bg);
                if (IOBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], threshold);
                if (IOBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        }

        //MP Disable the block below if tae is active - BG predictions are mostly wrong during UAM and can result in persistent highs - other safety features are in place
        if (!tsunamiResult.activityControllerActive && enableSMB && minGuardBG < threshold) {
            consoleError("minGuardBG ${convert_bg(minGuardBG)} projected below ${convert_bg(threshold)} - disabling SMB")
            //rT.reason += "minGuardBG "+minGuardBG+"<"+threshold+": SMB disabled; ";
            enableSMB = false
        }
        if (maxDelta > 0.20 * bg) {
            consoleError("maxDelta ${convert_bg(maxDelta)} > 20% of BG ${convert_bg(bg)} - disabling SMB")
            rT.reason.append("maxDelta " + convert_bg(maxDelta) + " > 20% of BG " + convert_bg(bg) + ": SMB disabled; ")
            enableSMB = false
        }

        consoleError("BG projected to remain above ${convert_bg(min_bg)} for $minutesAboveMinBG minutes")
        if (minutesAboveThreshold < 240 || minutesAboveMinBG < 60) {
            consoleError("BG projected to remain above ${convert_bg(threshold)} for $minutesAboveThreshold minutes")
        }
        // include at least minutesAboveThreshold worth of zero temps in calculating carbsReq
        // always include at least 30m worth of zero temp (carbs to 80, low temp up to target)
        val zeroTempDuration = minutesAboveThreshold
        // BG undershoot, minus effect of zero temps until hitting min_bg, converted to grams, minus COB
        val zeroTempEffectDouble = profile.current_basal * sens * zeroTempDuration / 60
        // don't count the last 25% of COB against carbsReq
        val COBforCarbsReq = max(0.0, meal_data.mealCOB - 0.25 * meal_data.carbs)
        val carbsReq = round(((bgUndershoot - zeroTempEffectDouble) / csf - COBforCarbsReq))
        val zeroTempEffect = round(zeroTempEffectDouble)
        consoleError("naive_eventualBG: $naive_eventualBG bgUndershoot: $bgUndershoot zeroTempDuration $zeroTempDuration zeroTempEffect: $zeroTempEffect carbsReq: $carbsReq")
        if (carbsReq >= profile.carbsReqThreshold && minutesAboveThreshold <= 45) {
            rT.carbsReq = carbsReq
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsReq add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        // don't low glucose suspend if IOB is already super negative and BG is rising faster than predicted
        if (bg < threshold && iob_data.iob < -profile.current_basal * 20 / 60 && minDelta > 0 && minDelta > expectedDelta) {
            rT.reason.append("IOB ${iob_data.iob} < ${round(-profile.current_basal * 20 / 60, 2)}")
            rT.reason.append(" and minDelta ${convert_bg(minDelta)} > expectedDelta ${convert_bg(expectedDelta)}; ")
            // predictive low glucose suspend mode: BG is / is projected to be < threshold

        } else if (!tsunamiResult.activityControllerActive && bg < threshold || minGuardBG < threshold) {
            //MP Disable the block below if tae is active - BG predictions are mostly wrong during UAM and can result in persistent highs - other safety features are in place
            rT.reason.append("minGuardBG " + convert_bg(minGuardBG) + "<" + convert_bg(threshold))
            bgUndershoot = target_bg - minGuardBG
            val worstCaseInsulinReq = bgUndershoot / sens
            var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
            durationReq = round(durationReq / 30.0) * 30
            // always set a 30-120m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
            durationReq = min(120, max(30, durationReq))
            return setTempBasal(0.0, durationReq, profile, rT, currenttemp)
        } else if (tsunamiResult.activityControllerActive && bg < threshold) {
            //MP added to have default behaviour also if tae is on, but prediction condition was removed
            rT.reason.append("minGuardBG " + convert_bg(minGuardBG) + "<" + convert_bg(threshold))
            bgUndershoot = target_bg - minGuardBG
            val worstCaseInsulinReq = bgUndershoot / sens
            var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
            durationReq = round(durationReq / 30.0) * 30
            // always set a 30-120m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
            durationReq = min(120, max(30, durationReq))
            return setTempBasal(0.0, durationReq, profile, rT, currenttemp)
        }

        // if not in LGS mode, cancel temps before the top of the hour to reduce beeping/vibration
        // console.error(profile.skip_neutral_temps, rT.deliverAt.getMinutes());
        val minutes = Instant.ofEpochMilli(rT.deliverAt!!).atZone(ZoneId.systemDefault()).toLocalDateTime().minute
        if (profile.skip_neutral_temps && minutes >= 55) {
            rT.reason.append("; Canceling temp at " + minutes + "m past the hour. ")
            return setTempBasal(0.0, 0, profile, rT, currenttemp)
        }
        if (!tsunamiResult.activityControllerActive) {
            //MP Bypass oref1 block below if TAE is active
            if (eventualBG < min_bg) { // if eventual BG is below target:
                rT.reason.append("Eventual BG ${convert_bg(eventualBG)} < ${convert_bg(min_bg)}")
                // if 5m or 30m avg BG is rising faster than expected delta
                if (minDelta > expectedDelta && minDelta > 0 && carbsReq == 0) {
                    // if naive_eventualBG < 40, set a 30m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
                    if (naive_eventualBG < 40) {
                        rT.reason.append(", naive_eventualBG < 40. ")
                        return setTempBasal(0.0, 30, profile, rT, currenttemp)
                    }
                    if (glucose_status.delta > minDelta) {
                        rT.reason.append(", but Delta ${convert_bg(tick.toDouble())} > expectedDelta ${convert_bg(expectedDelta)}")
                    } else {
                        rT.reason.append(", but Min. Delta ${minDelta.toFixed2()} > Exp. Delta ${convert_bg(expectedDelta)}")
                    }
                    if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                        rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                        return rT
                    } else {
                        rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                        return setTempBasal(basal, 30, profile, rT, currenttemp)
                    }
                }

                // calculate 30m low-temp required to get projected BG up to target
                // multiply by 2 to low-temp faster for increased hypo safety
                var insulinReq =
                    if (dynIsfMode) 2 * min(0.0, (eventualBG - target_bg) / future_sens)
                    else 2 * min(0.0, (eventualBG - target_bg) / sens)
                insulinReq = round(insulinReq, 2)
                // calculate naiveInsulinReq based on naive_eventualBG
                var naiveInsulinReq = min(0.0, (naive_eventualBG - target_bg) / sens)
                naiveInsulinReq = round(naiveInsulinReq, 2)
                if (minDelta < 0 && minDelta > expectedDelta) {
                    // if we're barely falling, newinsulinReq should be barely negative
                    val newinsulinReq = round((insulinReq * (minDelta / expectedDelta)), 2)
                    //console.error("Increasing insulinReq from " + insulinReq + " to " + newinsulinReq);
                    insulinReq = newinsulinReq
                }
                // rate required to deliver insulinReq less insulin over 30m:
                var rate = basal + (2 * insulinReq)
                rate = round_basal(rate)

                // if required temp < existing temp basal
                val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
                // if current temp would deliver a lot (30% of basal) less than the required insulin,
                // by both normal and naive calculations, then raise the rate
                val minInsulinReq = Math.min(insulinReq, naiveInsulinReq)
                if (insulinScheduled < minInsulinReq - basal * 0.3) {
                    rT.reason.append(", ${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} is a lot less than needed. ")
                    return setTempBasal(rate, 30, profile, rT, currenttemp)
                }
                if (currenttemp.duration > 5 && rate >= currenttemp.rate * 0.8) {
                    rT.reason.append(", temp ${currenttemp.rate} ~< req ${round(rate, 2)}U/hr. ")
                    return rT
                } else {
                    // calculate a long enough zero temp to eventually correct back up to target
                    if (rate <= 0) {
                        bgUndershoot = (target_bg - naive_eventualBG)
                        val worstCaseInsulinReq = bgUndershoot / sens
                        var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
                        if (durationReq < 0) {
                            durationReq = 0
                            // don't set a temp longer than 120 minutes
                        } else {
                            durationReq = round(durationReq / 30.0) * 30
                            durationReq = min(120, max(0, durationReq))
                        }
                        //console.error(durationReq);
                        if (durationReq > 0) {
                            rT.reason.append(", setting ${durationReq}m zero temp. ")
                            return setTempBasal(rate, durationReq, profile, rT, currenttemp)
                        }
                    } else {
                        rT.reason.append(", setting ${round(rate, 2)}U/hr. ")
                    }
                    return setTempBasal(rate, 30, profile, rT, currenttemp)
                }
            }
        }

        // if eventual BG is above min but BG is falling faster than expected Delta
        if (minDelta < expectedDelta) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                if (glucose_status.delta < minDelta) {
                    rT.reason.append(
                        "Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Delta ${convert_bg(tick.toDouble())} < Exp. Delta ${
                            convert_bg(expectedDelta)
                        }"
                    )
                } else {
                    rT.reason.append("Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Min. Delta ${minDelta.toFixed2()} < Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }
        // eventualBG or minPredBG is below max_bg
        if (min(eventualBG, minPredBG) < max_bg) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                rT.reason.append("${convert_bg(eventualBG)}-${convert_bg(minPredBG)} in range: no temp required")
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }

        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convert_bg(eventualBG) + " >= " + convert_bg(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                return rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                return setTempBasal(basal, 30, profile, rT, currenttemp)
            }
        } else { // otherwise, calculate 30m high-temp required to get projected BG down to target
            // insulinReq is the additional insulin required to get minPredBG down to target_bg
            //console.error(minPredBG,eventualBG);
            var insulinReq =
                if (tsunamiResult.activityControllerActive) tsunamiResult.insReq
                else if (dynIsfMode) round((min(minPredBG, eventualBG) - target_bg) / future_sens, 2)
                else round((min(minPredBG, eventualBG) - target_bg) / sens, 2)
            // if that would put us over max_iob, then reduce accordingly
            if (insulinReq > max_iob - iob_data.iob) {
                rT.reason.append("max_iob $max_iob, ")
                insulinReq = max_iob - iob_data.iob
                consoleLog("InsulinReq adjusted for max_iob!")
            }

            // rate required to deliver insulinReq more insulin over 30m:
            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)
            insulinReq = round(insulinReq, 3)
            rT.insulinReq = insulinReq
            //console.error(iob_data.lastBolusTime);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus
            val maxBolus: Double
            if (microBolusAllowed && enableSMB && bg > threshold) {
                // never bolus more than maxSMBBasalMinutes worth of basal
                val mealInsulinReq = round(meal_data.mealCOB / profile.carb_ratio, 3)
                if (iob_data.iob > mealInsulinReq && iob_data.iob > 0) {
                    consoleError("IOB ${iob_data.iob} > COB ${meal_data.mealCOB}; mealInsulinReq = $mealInsulinReq")
                    consoleError("profile.maxUAMSMBBasalMinutes: ${profile.maxUAMSMBBasalMinutes} profile.current_basal: ${profile.current_basal}")
                    if (tsunamiResult.activityControllerActive) {
                        maxBolus = tsunamiResult.smbCap
                    } else {
                        maxBolus = round(profile.current_basal * profile.maxUAMSMBBasalMinutes / 60, 1)
                    }

                } else {
                    consoleError("profile.maxSMBBasalMinutes: ${profile.maxSMBBasalMinutes} profile.current_basal: ${profile.current_basal}")
                    maxBolus = round(profile.current_basal * profile.maxSMBBasalMinutes / 60, 1)
                }
                // bolus 1/2 the insulinReq, up to maxBolus, rounding down to nearest bolus increment
                val roundSMBTo = 1 / profile.bolus_increment
                // JB: Give everything (just as orig tsunamo), maybe setting??
                val smbFactor = 1.0
                val microBolus = Math.floor(Math.min(insulinReq * smbFactor, maxBolus) * roundSMBTo) / roundSMBTo
                // calculate a long enough zero temp to eventually correct back up to target
                val smbTarget = target_bg
                val worstCaseInsulinReq = (smbTarget - (naive_eventualBG + minIOBPredBG) / 2.0) / sens
                var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)

                // if insulinReq > 0 but not enough for a microBolus, don't set an SMB zero temp
                if (insulinReq > 0 && microBolus < profile.bolus_increment) {
                    durationReq = 0
                }

                var smbLowTempReq = 0.0
                if (durationReq <= 0) {
                    durationReq = 0
                    // don't set an SMB zero temp longer than 60 minutes
                } else if (durationReq >= 30) {
                    durationReq = round(durationReq / 30.0) * 30
                    durationReq = min(60, max(0, durationReq))
                } else {
                    // if SMB durationReq is less than 30m, set a nonzero low temp
                    smbLowTempReq = round(basal * durationReq / 30.0, 2)
                    durationReq = 30
                }

                if (tsunamiResult.activityControllerActive) {
                    rT.reason.append("Microbolusing ${microBolus}U. ")
                }

                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxBolus) {
                    rT.reason.append("; maxBolus $maxBolus")
                }
                if (durationReq > 0) {
                    rT.reason.append("; setting ${durationReq}m low temp of ${smbLowTempReq}U/h")
                }
                rT.reason.append(". ")

                // seconds since last bolus
                val lastBolusAge = (systemTime - iob_data.lastBolusTime) / 1000.0
                //console.error(lastBolusAge);
                // allow SMBIntervals between 1 and 10 minutes
                val SMBInterval = min(10, max(1, profile.SMBInterval)) * 60.0   // in seconds
                //console.error(naive_eventualBG, insulinReq, worstCaseInsulinReq, durationReq);
                consoleError("naive_eventualBG $naive_eventualBG,${durationReq}m ${smbLowTempReq}U/h temp needed; last bolus ${round(lastBolusAge / 60.0, 1)}m ago; maxBolus: $maxBolus")
                if (lastBolusAge > SMBInterval - 6.0) {   // 6s tolerance
                    if (microBolus > 0) {
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${microBolus}U. ")
                    }
                    // JB: Moved below code into SMB check, we only want to check this when we can actually bolus
                    // if no zero temp is required, don't return yet; allow later code to set a high temp
                    if (durationReq > 0) {
                        rT.rate = smbLowTempReq
                        rT.duration = durationReq
                        return rT
                    }
                } else {
                    val nextBolusMins = (SMBInterval - lastBolusAge) / 60.0
                    val nextBolusSeconds = (SMBInterval - lastBolusAge) % 60
                    val waitingSeconds = round(nextBolusSeconds, 0) % 60
                    val waitingMins = round(nextBolusMins - waitingSeconds / 60.0, 0)
                    rT.reason.append("Waiting ${waitingMins.withoutZeros()}m ${waitingSeconds.withoutZeros()}s to microbolus again.")
                }
                //rT.reason += ". ";

                // if no zero temp is required, don't return yet; allow later code to set a high temp
                if (durationReq > 0) {
                    rT.rate = smbLowTempReq
                    rT.duration = durationReq
                    return rT
                }
            }

            val maxSafeBasal = getMaxSafeBasal(profile)

            if (rate > maxSafeBasal) {
                rT.reason.append("adj. req. rate: ${round(rate, 2)} to maxSafeBasal: ${maxSafeBasal.withoutZeros()}, ")
                rate = round_basal(maxSafeBasal)
            }

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            if (insulinScheduled >= insulinReq * 2) { // if current temp would deliver >2x more than the required insulin, lower the rate
                rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} > 2 * insulinReq. Setting temp basal of ${round(rate, 2)}U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration == 0) { // no temp is set
                rT.reason.append("no temp, setting " + round(rate, 2).withoutZeros() + "U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration > 5 && (round_basal(rate) <= round_basal(currenttemp.rate))) { // if required temp <~ existing temp basal
                rT.reason.append("temp ${(currenttemp.rate).toFixed2()} >~ req ${round(rate, 2).withoutZeros()}U/hr. ")
                return rT
            }

            // required temp > existing temp basal
            rT.reason.append("temp ${currenttemp.rate.toFixed2()} < ${round(rate, 2).withoutZeros()}U/hr. ")
            return setTempBasal(rate, 30, profile, rT, currenttemp)
        }
    }

    data class TsunamiActivityData(
        val futureActivity: Double = 0.0,
        val sensorLagActivity: Double = 0.0,
        val historicActivity: Double = 0.0,
        val currentActivity: Double = 0.0,
        val dia: Double = 0.0
    )

    data class TsunamiResult(
        val insReq: Double = 0.0,
        val smbCap: Double = 0.0,
        val activityControllerActive: Boolean = false,
        val reason: StringBuilder
    )

    private fun determineTsunamiInsReq(glucose_status: GlucoseStatus, target_bg: Double, adjusted_sens: Double, profile: OapsProfile, iob_data: IobTotal, currentTimeMillis: Long): TsunamiResult {
        //------------------------------- MP
        // TSUNAMI ACTIVITY ENGINE START  MP
        //------------------------------- MP

        // Settings:
        val enableWaveMode = preferences.get(BooleanKey.ApsWaveEnable)
        val waveInsReqPct = preferences.get(DoubleKey.ApsWaveInsReqPct)
        //    var startTime = preferences.get(IntKey.ApsWaveStartTime).toDouble()
        //    var endTime = preferences.get(IntKey.ApsWaveEndTime).toDouble()
        val maxWaveSMBBasalMinutes = preferences.get(IntKey.ApsWaveMaxMinutesOfBasalToLimitSmb)
        val waveUseSMBCap = preferences.get(BooleanKey.ApsWaveUseSMBCap)
        val waveSMBCap = preferences.get(DoubleKey.ApsWaveSmbCap)
        val waveUseAdjustedSens = false //preferences.get(BooleanKey.ApsWaveUseAdjustedSens)
        // val waveSMBCapScaling = sp.getBoolean(R.string.key_wave_SMB_scaling, false) // Leave for now

        var insulinReqPCT = waveInsReqPct / 100.0
        val deltaReductionPCT = 0.5 // JB: Default Tsunami Wave Reduction, maybe pref?
        // JB Note: activity_target not used in wavez

        val used_sens = if (waveUseAdjustedSens) {
            //JB TODO: Check if futuresens is needed when dynISF is active
            adjusted_sens
        } else {
            round(profile.sens, 1)
        }
        val bg = glucose_status.glucose

        if (!enableWaveMode) {
            return TsunamiResult(0.0, 0.0, false, StringBuilder())
        }



        var SMBcap = if (waveUseSMBCap) {
            waveSMBCap
        } else {
            round(profile.current_basal * maxWaveSMBBasalMinutes / 60, 1)
        }

        //MP Give SMBs that are 70% of SMBcap or more extra time to be absorbed before delivering another large SMB.
        val lastBolus = persistenceLayer.getNewestBolus()?.amount
        val lastBolusAge = round((currentTimeMillis - iob_data.lastBolusTime) / 60000.0, 1)
        if (lastBolusAge <= 9 && lastBolus != null && lastBolus >= 0.70 * SMBcap) {
            SMBcap = max(SMBcap - lastBolus, 0.0)
        }

        val activityData = getTsunamiActivityData()
        val act_curr = activityData.sensorLagActivity
        val act_future = activityData.futureActivity
        val pure_delta = round(min(glucose_status.delta + max(act_curr * used_sens, 0.0), 35.0), 1)
        val act_targetDelta = (pure_delta / used_sens) * deltaReductionPCT

        // JB Note: activity_target not used in wavez
        val act_missing = round((act_targetDelta - max(act_future, 0.0)) / 5, 4)

        var tsunami_insreq: Double
        var iterations = 0
        val insulin = activePlugin.activeInsulin
        if (!insulin.isPD) {
            // PK BASED MODEL CODE
            // MP Calculate the insulin required to neutralise the current delta in "peak-time" minutes
            val act_at_peak = insulin.iobCalcPeakForTreatment(BS(timestamp = 0, amount = 1.0, type = BS.Type.NORMAL), activityData.dia).activityContrib
            tsunami_insreq = act_missing / act_at_peak
        } else {
            // PD BASED MODEL CODE
            var actRatio = 1.0 //MP initialising value
            var act_at_peak = 0.000001 //MP Dummy value to enter while-loop
            tsunami_insreq = 1.0 //MP Initial guess

            //Iterative dose estimation (allowed rel. error: +/- 2%)
            if (act_missing != 0.0) {
                while (round(act_at_peak / act_missing, 2) > 1.02 || round(act_at_peak / act_missing, 2) < 0.98) {
                    tsunami_insreq = tsunami_insreq / actRatio
                    act_at_peak = insulin.iobCalcPeakForTreatment(BS(timestamp = 0, amount = tsunami_insreq, type = BS.Type.NORMAL), activityData.dia).activityContrib
                    actRatio = act_at_peak / act_missing
                    consoleLog("tsunami_insreq ($iterations): ${round(tsunami_insreq, 3)} | actRatio: ${round(actRatio, 3)}")
                    iterations++
                }
            } else {
                tsunami_insreq = 0.0
            }
        }

        iterations -= 1; //MP Minus 1 as the iterations are overcounted by 1 in the while loop

        val bg_correction = (bg - target_bg) / adjusted_sens
        if (bg_correction > iob_data.iob && bg_correction > tsunami_insreq) {
            tsunami_insreq = bg_correction
        }
        tsunami_insreq = round(tsunami_insreq, 2)

        //MP deltaScore and BG score
        val deltaScore: Double = min(1.0, max(glucose_status.shortAvgDelta / 4, 0.0)) //MP Modifies insulinReqPCT; deltaScore grows larger the largest the previous deltas were, until it reaches 1
        insulinReqPCT = round(insulinReqPCT * deltaScore, 3) //MP Modify insulinReqPCT in dependence of previous delta values
        var bgScore_upper_threshold = target_bg + 30 //MP BG above which no penalty will be given
        var bgScore_lower_threshold = target_bg //MP BG below which tae will not deliver SMBs
        var bgScore = round(Math.min((bg - bgScore_lower_threshold) / (bgScore_upper_threshold - bgScore_lower_threshold), 1.0), 3); //MP Penalty at low or near-target bg values. Modifies SMBcap.
        SMBcap = round(SMBcap * bgScore, 2)

        var activityControllerActive = false
        val reason: StringBuilder = StringBuilder()
        //MP Enable TAE SMB sizing if the safety conditions are all met

        if (WaveActief() &&
            //referenceTimer >= startTime &&
            //referenceTimer <= endTime &&
            glucose_status.delta >= 4.1 &&
            bg >= target_bg &&
            iob_data.iob > 0.1 &&
            act_curr > 0 &&
            tsunami_insreq + iob_data.iob >= (bg - target_bg) / used_sens
        ) {
            activityControllerActive = true

            consoleLog("------------------------------")
            consoleLog("WAVE STATUS")
            consoleLog("------------------------------")
            consoleLog("act. lag: ${activityData.sensorLagActivity}")
            consoleLog("act. now: $act_curr (${activityData.currentActivity})")
            consoleLog("act. future: ${activityData.futureActivity}")
            consoleLog("miss./act. future: $act_missing")
            consoleLog("-------------")
            consoleLog("bg: $bg")
            consoleLog("delta: ${glucose_status.delta}")
            consoleLog("pure delta: $pure_delta")
            consoleLog("-------------")
            consoleLog("deltaScore_live: ${round(deltaScore, 3)}")
            consoleLog("bgScore_live: $bgScore")
            consoleLog("insulinReqPCT_live: $insulinReqPCT")
            consoleLog("SMBcap_live: $SMBcap")
            consoleLog("tsunami_insreq: $tsunami_insreq")
            consoleLog("iterations: $iterations")
            if (bg_correction > iob_data.iob && bg_correction > tsunami_insreq) {
                consoleLog("Mode: IOB too low, correcting for BG.")
            } else {
                consoleLog("Mode: Building up activity.")
            }
            consoleLog("------------------------------")

            reason.append(" ##TSUNAMI STATUS##")
            reason.append(" act. lag: ${activityData.sensorLagActivity}")
            reason.append("; act. now: $act_curr (${activityData.currentActivity})")
            reason.append("; act. future: ${activityData.futureActivity}")
            reason.append("; miss./act. future: $act_missing")
            reason.append("; ###")
            reason.append(" bg: $bg")
            reason.append("; delta: ${glucose_status.delta}")
            reason.append("; pure delta: $pure_delta")
            reason.append("; ###")
            reason.append(" deltaScore_live: ${round(deltaScore, 3)}")
            reason.append("; bgScore_live: $bgScore")
            reason.append("; insulinReqPCT_live: $insulinReqPCT")
            reason.append("; SMBcap_live: $SMBcap")
            reason.append("; tsunami_insreq: $tsunami_insreq")
            reason.append("; iterations: $iterations")
            reason.append("; ###")
            if (bg_correction > iob_data.iob && bg_correction > tsunami_insreq) {
                reason.append(" Mode: IOB too low, correcting for BG.")
            } else {
                reason.append(" Mode: Building up activity.")
            }

            reason.append(" ##TSUNAMI STATUS END##")

        } else {
            // Reporting if TAE is bypassed
            consoleLog("------------------------------")
            consoleLog("WAVE STATUS")
            consoleLog("------------------------------")
            consoleLog("TAE bypassed - reasons:")
            //   if (referenceTimer < startTime || referenceTimer > endTime) {
            if (!WaveActief()) {
                consoleLog("Outside active hours.")
            }
            if (glucose_status.delta <= 4.1) {
                consoleLog("Delta too low. (${glucose_status.delta})")
            }
            if (bg < target_bg) {
                consoleLog("Glucose is below target.")
            }
            if (iob_data.iob <= 0.1) {
                consoleLog("IOB is below minimum of 0.1 U.")
            }
            if (act_curr <= 0) {
                consoleLog("Insulin activity is negative or 0.")
            }
            if (tsunami_insreq + iob_data.iob < (bg - target_bg) / used_sens) {
                consoleLog("Incompatible insulin & glucose status. Let oref1 take over for now.")
            }
            consoleLog("------------------------------")
        }

        tsunami_insreq = round(tsunami_insreq * insulinReqPCT, 2)
        return TsunamiResult(tsunami_insreq, SMBcap, activityControllerActive, reason)
    }

    private fun getTsunamiActivityData(): TsunamiActivityData {
        // Get peak time if using a PK insulin model
        // Calculate reference activity values
        val profile = profileFunction.getProfile() ?: return TsunamiActivityData()

        var currentActivity = 0.0
        for (i in -4..0) { //MP: -4 to 0 calculates all the insulin active during the last 5 minutes
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(i.toLong()), profile)
            currentActivity += iob.activity
        }

        var futureActivity = 0.0
        val insulin = activePlugin.activeInsulin
        // val activityPredTime: Long = if (!insulin.isPD) { //MP if not using PD insulin models
        //     // Get peak time if using a PK insulin model
        //     insulin.peak.toLong() //MP act. pred. time for PK ins. models; target time = insulin peak time
        // } else { //MP if using PD insulin models
        //     //MP activity prediction time for pharmacodynamic model; fixed to 65 min (approx. peak time of 1 U bolus)
        //     65L
        // }
        // JB: we have set PD peak times in insulin models, so we can use it directly here
        val activityPredTime = insulin.peak.toLong()
        for (i in -4..0) { //MP: calculate 5-minute-insulin activity centering around peaktime
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(activityPredTime - i), profile)
            futureActivity += iob.activity
        }

        val sensorLag = -10L //MP Assume that the glucose value measurement reflect the BG value from 'sensorlag' minutes ago & calculate the insulin activity then
        var sensorLagActivity = 0.0
        for (i in -4..0) {
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(sensorLag - i), profile)
            sensorLagActivity += iob.activity
        }

        val activityHistoric = -20L //MP Activity at the time in minutes from now. Used to calculate activity in the past to use as target activity.
        var historicActivity = 0.0
        for (i in -2..2) {
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(activityHistoric - i), profile)
            historicActivity += iob.activity
        }

        futureActivity = Round.roundTo(futureActivity, 0.0001)
        sensorLagActivity = Round.roundTo(sensorLagActivity, 0.0001)
        historicActivity = Round.roundTo(historicActivity, 0.0001)
        currentActivity = Round.roundTo(currentActivity, 0.0001)

        return TsunamiActivityData(futureActivity, sensorLagActivity, historicActivity, currentActivity, profile.dia)
    }
}
