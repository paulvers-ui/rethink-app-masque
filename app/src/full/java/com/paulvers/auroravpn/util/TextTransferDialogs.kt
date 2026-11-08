/*
 * Copyright 2026 AuroraVPN contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// UI helper: dp sizes and preview lengths are layout literals; clipboard and
// Binder calls throw undocumented RuntimeExceptions (e.g. a wrapped
// TransactionTooLargeException), so broad catches are deliberate and each one
// degrades to a user message instead of a crash.
@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "ReturnCount", "TooManyFunctions")

package com.paulvers.auroravpn.util

import Logger
import Logger.LOG_TAG_UI
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.paulvers.auroravpn.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Copy/paste UI for backups and WireGuard configs.
 *
 * Hardening, because the text is as sensitive as the file it replaces:
 *  - FLAG_SECURE on both dialogs: no screenshots, screen recording or
 *    thumbnails in Recents;
 *  - the clip is flagged sensitive (Android 13+ hides it from the clipboard
 *    preview) and is cleared after [CLIPBOARD_CLEAR_MS] if still ours;
 *  - oversized text is refused up front instead of crashing on the ~1 MiB
 *    Binder limit of the clipboard and of share intents.
 * Parsing and validation live in SecureTextTransfer.kt (pure, unit-tested).
 */
object TextTransferDialogs {

    private const val CLIP_LABEL = "AuroraVPN secure transfer"
    private const val CLIPBOARD_CLEAR_MS = 60_000L
    private const val PREVIEW_CHARS = 1_200

    /** Shows [text] with Copy / Share / Close. */
    fun showExport(ctx: Context, title: String, text: String) {
        val preview = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(false) // copy goes through the hardened path below
            val shown = if (text.length > PREVIEW_CHARS) text.take(PREVIEW_CHARS) + "\n…" else text
            setText(shown)
        }
        val info = TextView(ctx).apply {
            setText(ctx.getString(R.string.text_transfer_export_info, text.length))
            setPadding(0, 0, 0, dp(ctx, 8))
        }
        val column = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(info)
            addView(ScrollView(ctx).apply {
                addView(preview)
                layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 220))
            })
        }
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
            .setTitle(title)
            .setView(padded(ctx, column))
            .setPositiveButton(R.string.text_transfer_copy, null)
            .setNeutralButton(R.string.text_transfer_share, null)
            .setNegativeButton(R.string.lbl_dismiss, null)
            .create()
        secure(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (copySensitive(ctx, text)) dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (shareText(ctx, title, text)) dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Shows a paste field. [onSubmit] validates synchronously and returns an
     * error message to keep the dialog open, or null to accept and close.
     */
    fun showImport(
        ctx: Context,
        title: String,
        hint: String,
        maxChars: Int,
        onSubmit: (String) -> String?
    ) {
        val input = TextInputEditText(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(false)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(maxChars))
            // Keyboards must not learn or sync what is pasted here.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
            imeOptions = imeOptions or 0x1000000 // EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val layout = TextInputLayout(ctx).apply {
            this.hint = hint
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
            .setTitle(title)
            .setView(padded(ctx, layout))
            .setPositiveButton(R.string.text_transfer_import, null)
            .setNeutralButton(R.string.text_transfer_paste, null)
            .setNegativeButton(R.string.lbl_cancel, null)
            .create()
        secure(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clip = readClipboard(ctx)
                when {
                    clip == null -> layout.error = ctx.getString(R.string.text_transfer_clipboard_empty)
                    clip.length > maxChars -> layout.error = ctx.getString(R.string.text_transfer_err_too_large)
                    else -> { layout.error = null; input.setText(clip); input.setSelection(0) }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val error = try {
                    onSubmit(input.text?.toString().orEmpty())
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_UI, "text import: unexpected error: ${e.message}", e)
                    ctx.getString(R.string.text_transfer_err_generic)
                }
                if (error == null) {
                    input.setText("") // drop the secret from the view before closing
                    dialog.dismiss()
                } else {
                    layout.error = error
                }
            }
        }
        dialog.show()
    }

    /** Maps a parse failure to a user-facing message. */
    fun message(ctx: Context, error: TransferError): String = ctx.getString(
        when (error) {
            TransferError.EMPTY -> R.string.text_transfer_err_empty
            TransferError.TOO_LARGE -> R.string.text_transfer_err_too_large
            TransferError.CONTROL_CHARACTERS -> R.string.text_transfer_err_control
            TransferError.NOT_BASE64 -> R.string.text_transfer_err_not_base64
            TransferError.WRONG_KIND -> R.string.text_transfer_err_wrong_kind
            TransferError.TRUNCATED -> R.string.text_transfer_err_truncated
            TransferError.CHECKSUM_MISMATCH -> R.string.text_transfer_err_checksum
            TransferError.NOT_A_BACKUP -> R.string.text_transfer_err_not_backup
            TransferError.NOT_WIREGUARD -> R.string.text_transfer_err_not_wireguard
        }
    )

    private fun copySensitive(ctx: Context, text: String): Boolean {
        if (text.length > BackupText.MAX_CLIPBOARD_CHARS) {
            toast(ctx, ctx.getString(R.string.text_transfer_err_too_large_for_clipboard))
            return false
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return try {
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            // Hides the content from the Android 13+ clipboard preview overlay.
            // (ClipDescription#setExtras exists from API 24; minSdk is 23.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            cm.setPrimaryClip(clip)
            scheduleClipboardClear(cm)
            toast(ctx, ctx.getString(R.string.text_transfer_copied, (CLIPBOARD_CLEAR_MS / 1000).toInt()))
            true
        } catch (e: Exception) { // e.g. TransactionTooLargeException wrapped in RuntimeException
            Logger.w(LOG_TAG_UI, "text export: copy failed: ${e.message}")
            toast(ctx, ctx.getString(R.string.text_transfer_err_too_large_for_clipboard))
            false
        }
    }

    private fun scheduleClipboardClear(cm: ClipboardManager) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Only clear if the clip is still ours; never wipe something the user copied later.
                if (cm.primaryClipDescription?.label == CLIP_LABEL) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cm.clearPrimaryClip()
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "clipboard clear skipped: ${e.message}")
            }
        }, CLIPBOARD_CLEAR_MS)
    }

    private fun readClipboard(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return try {
            val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
            item.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "clipboard read failed: ${e.message}")
            null
        }
    }

    private fun shareText(ctx: Context, title: String, text: String): Boolean {
        if (text.length > BackupText.MAX_CLIPBOARD_CHARS) {
            toast(ctx, ctx.getString(R.string.text_transfer_err_too_large_for_clipboard))
            return false
        }
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "text export: share failed: ${e.message}")
            toast(ctx, ctx.getString(R.string.text_transfer_err_generic))
            false
        }
    }

    private fun secure(dialog: AlertDialog) {
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun padded(ctx: Context, child: android.view.View): FrameLayout =
        FrameLayout(ctx).apply {
            val p = dp(ctx, 20)
            setPadding(p, dp(ctx, 8), p, 0)
            addView(child)
        }

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun toast(ctx: Context, msg: String) {
        Utilities.showToastUiCentered(ctx, msg, Toast.LENGTH_LONG)
    }
}
