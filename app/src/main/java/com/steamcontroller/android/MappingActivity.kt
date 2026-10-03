package com.steamcontroller.android

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.steamcontroller.android.databinding.ActivityMappingBinding
import com.steamcontroller.android.input.ButtonCategory
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.XboxTarget

/**
 * Each row shows a Kenney input-prompts icon when one exists, falling back
 * to a colored letter chip otherwise. Source uses the white SC icon; target uses
 * the official Steam Controller colored A/B/X/Y or a neutral letter for everything
 * else. The entire row is clickable and opens a single-choice remap dialog.
 */
class MappingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMappingBinding
    private val desktopMode by lazy { Prefs.getProfile(this).isMouseMode }
    private val targets by lazy {
        XboxTarget.values().filter { target ->
            if (desktopMode) {
                target == XboxTarget.NONE || target.keyBit >= 0 || target == XboxTarget.SCREENSHOT
            } else {
                target.keyBit < 16
            }
        }
    }
    private val targetNames by lazy { targets.map { it.displayName } }

    private data class RowViews(
        val targetIcon: ImageView,
        val targetBadge: TextView,
        val targetName: TextView
    )
    private val rowsBySource = mutableMapOf<SteamButton, RowViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.subtitle = if (desktopMode) "Desktop actions" else "Gamepad actions"
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reset -> {
                    if (desktopMode) Prefs.resetDesktopMappings(this) else Prefs.resetMappings(this)
                    rebuild()
                    true
                }

                R.id.action_profiles -> {
                    startActivity(Intent(this, ProfilesActivity::class.java))
                    true
                }

                else -> false
            }
        }

        rebuild()
    }

    private fun rebuild() {
        binding.mappingContainer.removeAllViews()
        rowsBySource.clear()

        val grouped = SteamButton.values().groupBy { it.category }
        for (category in ButtonCategory.values()) {
            val buttons = grouped[category] ?: continue
            addSectionHeader(category.title)
            for (source in buttons) addRow(source)
        }
    }

    private fun addSectionHeader(title: String) {
        val view = layoutInflater.inflate(
            R.layout.item_mapping_section,
            binding.mappingContainer,
            false
        )
        view.findViewById<TextView>(R.id.tvSectionTitle).text = title
        binding.mappingContainer.addView(view)
    }

    private fun addRow(source: SteamButton) {
        val row = layoutInflater.inflate(R.layout.item_mapping_row, binding.mappingContainer, false)

        applySourceChip(
            row.findViewById(R.id.ivSourceIcon),
            row.findViewById(R.id.tvSourceBadge),
            source
        )
        row.findViewById<TextView>(R.id.tvSourceName).text = source.displayName

        val targetIcon = row.findViewById<ImageView>(R.id.ivTargetIcon)
        val targetBadge = row.findViewById<TextView>(R.id.tvTargetBadge)
        val targetName = row.findViewById<TextView>(R.id.tvTargetName)

        applyTargetChip(targetIcon, targetBadge, targetName, getMapping(source))

        row.setOnClickListener { showRemapDialog(source) }

        rowsBySource[source] = RowViews(targetIcon, targetBadge, targetName)
        binding.mappingContainer.addView(row)
    }

    private fun applySourceChip(icon: ImageView, badge: TextView, source: SteamButton) {
        showIconOrBadge(
            icon,
            badge,
            sourceIconFor(source),
            source.shortLabel,
            ContextCompat.getColor(this, R.color.btn_neutral),
        )
    }

    /** A chip shows the button's glyph when there is one, otherwise a coloured text badge. */
    private fun showIconOrBadge(
        icon: ImageView,
        badge: TextView,
        iconRes: Int?,
        badgeText: String,
        badgeColor: Int,
    ) {
        if (iconRes != null) {
            icon.setImageResource(iconRes)
            icon.visibility = View.VISIBLE
            badge.visibility = View.GONE
        } else {
            badge.text = badgeText
            badge.backgroundTintList = ColorStateList.valueOf(badgeColor)
            badge.visibility = View.VISIBLE
            icon.visibility = View.GONE
        }
    }

    private fun applyTargetChip(
        icon: ImageView,
        badge: TextView,
        label: TextView,
        target: XboxTarget
    ) {
        showIconOrBadge(icon, badge, targetIconFor(target), shortLabelFor(target), colorFor(target))
        label.text = target.displayName
    }

    private fun getMapping(source: SteamButton): XboxTarget =
        if (desktopMode) Prefs.getDesktopMapping(this, source) else Prefs.getMapping(this, source)

    private fun setMapping(source: SteamButton, target: XboxTarget) {
        if (desktopMode) {
            Prefs.setDesktopMapping(this, source, target)
        } else {
            Prefs.setMapping(this, source, target)
        }
    }

    private fun showRemapDialog(source: SteamButton) {
        val current = getMapping(source)
        val currentIndex = targets.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Remap ${source.displayName}")
            .setSingleChoiceItems(targetNames.toTypedArray(), currentIndex) { dialog, which ->
                val picked = targets[which]
                setMapping(source, picked)
                rowsBySource[source]?.let {
                    applyTargetChip(it.targetIcon, it.targetBadge, it.targetName, picked)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    /** Kenney SC icon for each source button. null → fall back to letter chip. */
    private fun sourceIconFor(b: SteamButton): Int? = when (b) {
        SteamButton.A -> R.drawable.sc_btn_a
        SteamButton.B -> R.drawable.sc_btn_b
        SteamButton.X -> R.drawable.sc_btn_x
        SteamButton.Y -> R.drawable.sc_btn_y
        SteamButton.LB -> R.drawable.sc_btn_lb
        SteamButton.RB -> R.drawable.sc_btn_rb
        SteamButton.LT -> R.drawable.sc_btn_lt
        SteamButton.RT -> R.drawable.sc_btn_rt
        SteamButton.MENU -> R.drawable.sc_btn_menu
        SteamButton.VIEW -> R.drawable.sc_btn_view
        SteamButton.STEAM -> R.drawable.sc_btn_steam
        SteamButton.QUICK_ACCESS -> R.drawable.sc_btn_qa
        SteamButton.L4 -> R.drawable.sc_btn_l4
        SteamButton.L5 -> R.drawable.sc_btn_l5
        SteamButton.R4 -> R.drawable.sc_btn_r4
        SteamButton.R5 -> R.drawable.sc_btn_r5
        SteamButton.GRIP_LT -> R.drawable.sc_btn_lg
        SteamButton.GRIP_RT -> R.drawable.sc_btn_rg
        SteamButton.LS, SteamButton.RS -> R.drawable.sc_btn_stick_press
    }

    /** Steam Input icons for every Xbox-native target. Keyboard / screenshot / NONE
     *  fall through to the colored letter chip. */
    private fun targetIconFor(t: XboxTarget): Int? = when (t) {
        XboxTarget.A -> R.drawable.sc_btn_a_color
        XboxTarget.B -> R.drawable.sc_btn_b_color
        XboxTarget.X -> R.drawable.sc_btn_x_color
        XboxTarget.Y -> R.drawable.sc_btn_y_color
        XboxTarget.LB -> R.drawable.xbox_btn_lb
        XboxTarget.RB -> R.drawable.xbox_btn_rb
        XboxTarget.LT_TRIGGER -> R.drawable.sc_btn_lt
        XboxTarget.RT_TRIGGER -> R.drawable.sc_btn_rt
        XboxTarget.SELECT -> R.drawable.xbox_btn_select
        XboxTarget.START -> R.drawable.xbox_btn_start
        XboxTarget.MODE -> R.drawable.xbox_btn_logo
        XboxTarget.THUMBL -> R.drawable.xbox_btn_l3
        XboxTarget.THUMBR -> R.drawable.xbox_btn_r3
        else -> null
    }

    /** Compact label for chip fallback. */
    private fun shortLabelFor(t: XboxTarget): String = when (t) {
        XboxTarget.NONE -> "-"
        XboxTarget.A -> "A"
        XboxTarget.B -> "B"
        XboxTarget.X -> "X"
        XboxTarget.Y -> "Y"
        XboxTarget.LB -> "L1"
        XboxTarget.RB -> "R1"
        XboxTarget.LT_TRIGGER -> "L2"
        XboxTarget.RT_TRIGGER -> "R2"
        XboxTarget.SELECT -> "···"
        XboxTarget.START -> "≡"
        XboxTarget.MODE -> "◆"
        XboxTarget.THUMBL -> "L3"
        XboxTarget.THUMBR -> "R3"
        XboxTarget.SCREENSHOT -> "📸"
        XboxTarget.GUIDE_LAYER -> "LYR"
        XboxTarget.KB_UP -> "↑"
        XboxTarget.KB_DOWN -> "↓"
        XboxTarget.KB_LEFT -> "←"
        XboxTarget.KB_RIGHT -> "→"
        XboxTarget.KB_VOLUME_UP -> "V+"
        XboxTarget.KB_VOLUME_DOWN -> "V-"
        XboxTarget.KB_PLAY_PAUSE -> "⏯"
        XboxTarget.KB_BACK -> "⮌"
        XboxTarget.KB_HOME -> "🏠"
        XboxTarget.KB_ENTER -> "↵"
        XboxTarget.KB_DPAD_CENTER -> "●"
        XboxTarget.KB_ESCAPE -> "ESC"
        XboxTarget.KB_TAB -> "⇥"
        XboxTarget.KB_SPACE -> "␣"
        XboxTarget.KB_BACKSPACE -> "⌫"
        XboxTarget.KB_MENU -> "☰"
        XboxTarget.MOUSE_LEFT -> "M1"
        XboxTarget.MOUSE_RIGHT -> "M2"
        XboxTarget.MOUSE_MIDDLE -> "M3"
    }

    private fun colorFor(t: XboxTarget): Int = ContextCompat.getColor(
        this,
        when (t) {
            XboxTarget.NONE -> R.color.chip_inactive
            else -> R.color.btn_neutral
        }
    )
}
