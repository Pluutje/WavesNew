package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogInsulinBinding
import app.aaps.ui.extensions.toSignedString
import com.google.common.base.Joiner
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.text.DecimalFormat
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

class InsulinDialog : DialogFragmentWithDate() {

    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var ctx: Context
    @Inject lateinit var config: Config
    @Inject lateinit var automation: Automation
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var loop: Loop

    private var queryingProtection = false
    private val disposable = CompositeDisposable()
    private var _binding: DialogInsulinBinding? = null
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
    private val ActExtraIns = File(externalDir, "ANALYSE/Act-extra-ins.txt")
    private val BolusViaSMB = File(externalDir, "ANALYSE/Bolus-via-smb.txt")
    private val BolusOverzicht = File(externalDir, "ANALYSE/BolusOverzicht.txt")



    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let {
                validateInputs()
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }

    private fun validateInputs() {
        val maxInsulin = constraintChecker.getMaxBolusAllowed().value()
        if (abs(binding.time.value.toInt()) > 12 * 60) {
            binding.time.value = 0.0
            ToastUtils.warnToast(context, app.aaps.core.ui.R.string.constraint_applied)
        }
        if (binding.amount.value > maxInsulin) {
            binding.amount.value = 0.0
            ToastUtils.warnToast(context, R.string.bolus_constraint_applied)
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putDouble("time", binding.time.value)
        savedInstanceState.putDouble("amount", binding.amount.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogInsulinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pump = activePlugin.activePump
        if (config.AAPSCLIENT) {
            binding.recordOnly.isChecked = true
            binding.recordOnly.isEnabled = false
        }
        val maxInsulin = constraintChecker.getMaxBolusAllowed().value()

        if (loop.isDisconnected || pump.isSuspended() || !pump.isInitialized()) {
            binding.recordOnly.isChecked = true
            binding.recordOnly.isEnabled = false
            binding.recordOnly.setTextColor(rh.gac(app.aaps.core.ui.R.attr.warningColor))
            //    binding.header.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.ribbonWarningColor))
            //    binding.headerText.setTextColor(rh.gac(app.aaps.core.ui.R.attr.ribbonTextWarningColor))
        }

        binding.time.setParams(
            savedInstanceState?.getDouble("time")
                ?: 0.0, -12 * 60.0, 12 * 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )
        binding.tijd.setParams(
            savedInstanceState?.getDouble("tijd")
                ?: 100.0, 20.0, 240.0, 10.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )
        binding.percentage.setParams(
            savedInstanceState?.getDouble("percentage")
                ?:225.0, 50.0, 500.0, 25.0, DecimalFormat("0"), true, binding.okcancel.ok, textWatcher
        )
        binding.aantalsmb.setParams(
            savedInstanceState?.getDouble("aantal")
                ?: 5.0, 1.0, 12.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

       // binding.editTextInput.text


        binding.amount.setParams(
            savedInstanceState?.getDouble("amount")
                ?: 0.0, 0.0, maxInsulin, activePlugin.activePump.pumpDescription.bolusStep, decimalFormatter.pumpSupportedBolusFormat(activePlugin.activePump.pumpDescription.bolusStep),
            false, binding.okcancel.ok, textWatcher
        )

        val plus05Text = preferences.get(DoubleKey.OverviewInsulinButtonIncrement1).toSignedString(activePlugin.activePump, decimalFormatter)
        binding.plus05.text = plus05Text
        binding.plus05.contentDescription = rh.gs(app.aaps.core.ui.R.string.overview_insulin_label) + " " + plus05Text
        binding.plus05.setOnClickListener {
            binding.amount.value = max(0.0, binding.amount.value + preferences.get(DoubleKey.OverviewInsulinButtonIncrement1))
            validateInputs()
            binding.amount.announceValue()
        }
        val plus10Text = preferences.get(DoubleKey.OverviewInsulinButtonIncrement2).toSignedString(activePlugin.activePump, decimalFormatter)
        binding.plus10.text = plus10Text
        binding.plus10.contentDescription = rh.gs(app.aaps.core.ui.R.string.overview_insulin_label) + " " + plus10Text
        binding.plus10.setOnClickListener {
            binding.amount.value = max(0.0, binding.amount.value + preferences.get(DoubleKey.OverviewInsulinButtonIncrement2))
            validateInputs()
            binding.amount.announceValue()
        }
        val plus20Text = preferences.get(DoubleKey.OverviewInsulinButtonIncrement3).toSignedString(activePlugin.activePump, decimalFormatter)
        binding.plus20.text = plus20Text
        binding.plus20.contentDescription = rh.gs(app.aaps.core.ui.R.string.overview_insulin_label) + " " + plus20Text
        binding.plus20.setOnClickListener {
            binding.amount.value = max(0.0, binding.amount.value + preferences.get(DoubleKey.OverviewInsulinButtonIncrement3))
            validateInputs()
            binding.amount.announceValue()
        }

        binding.timeLayout.visibility = View.GONE
        binding.recordOnly.setOnCheckedChangeListener { _, isChecked: Boolean ->
            binding.timeLayout.visibility = isChecked.toVisibility()
        }
        binding.tijdLayout.visibility = View.VISIBLE //View.GONE
        binding.percentageLayout.visibility = View.VISIBLE //View.GONE

        binding.aantalsmbLayout.visibility = View.VISIBLE
        binding.uitgesteldbasaalLayout.visibility = View.VISIBLE

        binding.insulinLabel.labelFor = binding.amount.editTextId
        binding.timeLabel.labelFor = binding.time.editTextId


        if (!binding.recordOnly.isChecked) {
            binding.timeLayout.visibility = View.GONE
        }
        binding.recordOnly.setOnCheckedChangeListener { _, isChecked: Boolean ->
            binding.timeLayout.visibility = isChecked.toVisibility()
        }
        binding.insulinLabel.labelFor = binding.amount.editTextId
        binding.timeLabel.labelFor = binding.time.editTextId
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
        _binding = null
    }

    override fun submit(): Boolean {
        if (_binding == null) return false
        val pumpDescription = activePlugin.activePump.pumpDescription
        var insulin = SafeParse.stringToDouble(binding.amount.text)
        var insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        val actions: LinkedList<String?> = LinkedList()
        val units = profileFunction.getUnits()
        val unitLabel = if (units == GlucoseUnit.MMOL) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl)
        val recordOnlyChecked = binding.recordOnly.isChecked
        val eatingSoonChecked = binding.startEatingSoonTt.isChecked
       // val opmerking = binding.editTextInput.text.toString()

        // Eigen input
        val extraInsulineChecked = binding.activeerExtraInsuline.isChecked
        val bolusviasmbChecked = binding.bolusViaSmb.isChecked
        val alleenBoostChecked = binding.geenBolus.isChecked
        val StopBolusChecked = binding.stopBolus.isChecked
        val aantalSMB = (binding.aantalsmb.value.toInt()).toString()
        val tijdExtraInsuline = (binding.tijd.value.toInt()).toString()
        val percentageExtraInsuline = (binding.percentage.value.toInt()).toString()
        val tijdNu =  (System.currentTimeMillis() / (60 * 1000)).toString()
        val insuline = insulinAfterConstraints.toString()
        //  val path = File(Environment.getExternalStorageDirectory().toString())
        //  val file = File(path, "Documents/AAPS/ANALYSE/Act-extra-ins.txt")
        //  val filebasaal = File(path, "Documents/AAPS/ANALYSE/Bolus-via-basaal.txt")

        // Zorg dat de map bestaat
        if (!ActExtraIns.parentFile.exists()) {
            ActExtraIns.parentFile.mkdirs()
        }

        if (extraInsulineChecked){
            ActExtraIns.writeText("checked" + "\n" + tijdNu + "\n" + tijdExtraInsuline + "\n" + percentageExtraInsuline)
        } else {
            ActExtraIns.writeText("unchecked" + "\n" + tijdNu + "\n" + tijdExtraInsuline + "\n" + percentageExtraInsuline)
        }
        if (StopBolusChecked) {
            ActExtraIns.writeText("unchecked" + "\n" + tijdNu + "\n" + tijdExtraInsuline + "\n" + percentageExtraInsuline)
        }


        if (alleenBoostChecked)  {
            insulin = 0.0
            insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        }

        if (!BolusViaSMB.parentFile.exists()) {
            BolusViaSMB.parentFile.mkdirs()
        }


        if (bolusviasmbChecked && insulin > 0.0) {
            insulin = 0.0
            insulinAfterConstraints = 0.0

            BolusViaSMB.writeText("checked" + "\n" + tijdNu + "\n" + aantalSMB + "\n" + insuline)
            val opmerking = binding.editTextInput.text.toString()
            schrijfBolusOverzicht(insuline, opmerking)
        } else {
            BolusViaSMB.writeText("unchecked" + "\n" + tijdNu + "\n" + aantalSMB + "\n" + insuline)
        }

        if (StopBolusChecked) {
            BolusViaSMB.writeText("unchecked" + "\n" + tijdNu + "\n" + aantalSMB + "\n" + insuline)
        }
        val eh_per_smb = round(insuline.toDouble()/aantalSMB.toDouble(),2).toString()
// Einde eigen input


        if (insulinAfterConstraints > 0) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + decimalFormatter.toPumpSupportedBolus(insulinAfterConstraints, activePlugin.activePump.pumpDescription.bolusStep)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
            )
            if (recordOnlyChecked)
                actions.add(rh.gs(app.aaps.core.ui.R.string.bolus_recorded_only).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
            if (abs(insulinAfterConstraints - insulin) > pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints))
                actions.add(
                    rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, insulin, insulinAfterConstraints).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
                )
        }
        val eatingSoonTTDuration = preferences.get(IntKey.OverviewEatingSoonDuration)
        val eatingSoonTT = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)
        if (eatingSoonChecked)
            actions.add(
                rh.gs(R.string.temp_target_short) + ": " + (decimalFormatter.to1Decimal(eatingSoonTT) + " " + unitLabel + " (" + rh.gs(
                    app.aaps.core.ui.R.string.format_mins,
                    eatingSoonTTDuration
                ) + ")")
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.tempTargetConfirmation)
            )

        val timeOffset = binding.time.value.toInt()
        val time = dateUtil.now() + T.mins(timeOffset.toLong()).msecs()
        if (timeOffset != 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(time))

        val notes = binding.notesLayout.notes.text.toString()
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)

        if (insulinAfterConstraints > 0 || eatingSoonChecked) {
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.bolus), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                    if (eatingSoonChecked) {
                        disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                            TT(
                                timestamp = System.currentTimeMillis(),
                                duration = TimeUnit.MINUTES.toMillis(eatingSoonTTDuration.toLong()),
                                reason = TT.Reason.EATING_SOON,
                                lowTarget = profileUtil.convertToMgdl(eatingSoonTT, profileFunction.getUnits()),
                                highTarget = profileUtil.convertToMgdl(eatingSoonTT, profileFunction.getUnits())
                            ),
                            action = Action.TT, source = Sources.InsulinDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.TETTReason(TT.Reason.EATING_SOON),
                                ValueWithUnit.fromGlucoseUnit(eatingSoonTT, units),
                                ValueWithUnit.Minute(eatingSoonTTDuration)
                            )
                        ).subscribe()
                    }
                    if (insulinAfterConstraints > 0) {
                        val detailedBolusInfo = DetailedBolusInfo()
                        detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
                        detailedBolusInfo.insulin = insulinAfterConstraints
                        detailedBolusInfo.context = context
                        detailedBolusInfo.notes = notes
                        detailedBolusInfo.timestamp = time
                        if (recordOnlyChecked) {
                            disposable += persistenceLayer.insertOrUpdateBolus(
                                bolus = detailedBolusInfo.createBolus(),
                                action = Action.BOLUS,
                                source = Sources.InsulinDialog,
                                note = rh.gs(app.aaps.core.ui.R.string.record) + if (notes.isNotEmpty()) ": $notes" else ""
                            ).subscribe()
                            if (timeOffset == 0)
                                automation.removeAutomationEventBolusReminder()
                        } else {
                            uel.log(
                                Action.BOLUS, Sources.InsulinDialog,
                                notes,
                                ValueWithUnit.Insulin(insulinAfterConstraints)
                            )
                            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                                override fun run() {
                                    if (!result.success) {
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                    } else {
                                        automation.removeAutomationEventBolusReminder()
                                    }
                                }
                            })
                        }
                    }
                })
            }
        } else {

            if (bolusviasmbChecked && !alleenBoostChecked && !StopBolusChecked) {
                activity?.let { activity ->
                    OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), " $insuline eh bolus wordt gegeven via $aantalSMB smb's van $eh_per_smb eh per smb")
                }
            } else {
                if (alleenBoostChecked || StopBolusChecked) {
                    if (alleenBoostChecked) {
                        activity?.let { activity ->
                            OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), " Geen Bolus, uitsluitend boost")
                        }
                    } else {
                        activity?.let { activity ->
                            OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), " Stop Bolus en Boost")
                        }

                    }
                } else {
                    activity?.let { activity ->
                        OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
                    }
                }
            }
        }
        return true
    }

    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun schrijfBolusOverzicht( insuline: String, opm: String) {
        val kopregel = " Datum     Tijd  -  Bolus"
        val tijdact = getFormattedTime()
        val nieuweRegel = "$tijdact - $insuline eh\n  opm: $opm"

        val file = BolusOverzicht

        if (!file.exists()) {
            // Bestand maken en kopregel + eerste regel schrijven
            file.writeText("$kopregel\n$nieuweRegel\n")
        } else {
            // Lees de bestaande inhoud
            var regels = file.readLines().toMutableList()

            // Zorg dat de kopregel correct is
            if (regels.isEmpty() || regels[0] != kopregel) {
                regels.add(0, kopregel) // Voeg kopregel toe als deze ontbreekt
            }

            // Voeg de nieuwe regel direct na de kopregel toe
            regels.add(1, nieuweRegel)

            // **Beperk de totale regels tot maximaal 40 (inclusief kopregel)**
            if (regels.size > 40) {
                regels = regels.subList(0, 40) // Houd alleen de eerste 40 regels
            }

            // Schrijf alles opnieuw naar het bestand
            file.writeText(regels.joinToString("\n"))
        }
    }

    fun getFormattedTime(): String {
        val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
        return dateFormat.format(Date()) // Huidige tijd in het gewenste formaat
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}