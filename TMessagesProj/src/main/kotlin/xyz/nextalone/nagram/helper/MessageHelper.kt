package xyz.nextalone.nagram.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import androidx.core.content.FileProvider
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.Emoji
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC.Chat
import org.telegram.tgnet.TLRPC.TL_messageEntityBankCard
import org.telegram.tgnet.TLRPC.TL_messageEntityBotCommand
import org.telegram.tgnet.TLRPC.TL_messageEntityCashtag
import org.telegram.tgnet.TLRPC.TL_messageEntityEmail
import org.telegram.tgnet.TLRPC.TL_messageEntityHashtag
import org.telegram.tgnet.TLRPC.TL_messageEntityMention
import org.telegram.tgnet.TLRPC.TL_messageEntityPhone
import org.telegram.tgnet.TLRPC.TL_messageEntitySpoiler
import org.telegram.tgnet.TLRPC.TL_messageEntityUrl
import org.telegram.tgnet.TLRPC.TL_messageMediaPoll
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ColoredImageSpan
import xyz.nextalone.nagram.NaConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Date


object MessageHelper {

    private val spannedStrings = arrayOfNulls<SpannableStringBuilder>(5)

    fun getPathToMessage(messageObject: MessageObject): File? {
        var path = messageObject.messageOwner.attachPath
        if (!TextUtils.isEmpty(path)) {
            val file = File(path)
            if (file.exists()) {
                return file
            } else {
                path = null
            }
        }
        if (TextUtils.isEmpty(path)) {
            val file = FileLoader.getInstance(messageObject.currentAccount)
                .getPathToMessage(messageObject.messageOwner)
            if (file != null && file.exists()) {
                return file
            } else {
                path = null
            }
        }
        if (TextUtils.isEmpty(path)) {
            val file = FileLoader.getInstance(messageObject.currentAccount)
                .getPathToAttach(messageObject.document, true)
            return if (file != null && file.exists()) {
                file
            } else {
                null
            }
        }
        return null
    }


    fun addMessageToClipboard(selectedObject: MessageObject, callback: Runnable) {
        val file = getPathToMessage(selectedObject)
        if (file != null) {
            if (file.exists()) {
                addFileToClipboard(file, callback)
            }
        }
    }

    fun addMessageToClipboardAsSticker(selectedObject: MessageObject, callback: Runnable) {
        val file = getPathToMessage(selectedObject)
        try {
            if (file != null) {
                val path = file.path
                val image = BitmapFactory.decodeFile(path)
                if (image != null && !TextUtils.isEmpty(path)) {
                    val file2 = File(
                        if (path.endsWith(".jpg")) path.replace(
                            ".jpg",
                            ".webp"
                        ) else "$path.webp"
                    )
                    val stream = FileOutputStream(file2)
                    if (Build.VERSION.SDK_INT >= 30) {
                        image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, stream)
                    } else {
                        @Suppress("DEPRECATION")
                        image.compress(Bitmap.CompressFormat.WEBP, 100, stream)
                    }
                    stream.close()
                    addFileToClipboard(file2, callback)
                }
            }
        } catch (ignored: java.lang.Exception) {
        }
    }

    fun addFileToClipboard(file: File?, callback: Runnable) {
        try {
            val context = ApplicationLoader.applicationContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val uri = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                file!!
            )
            val clip = ClipData.newUri(context.contentResolver, "label", uri)
            clipboard.setPrimaryClip(clip)
            callback.run()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun showForwardDate(obj: MessageObject, orig: String): String {
        val date: Long = obj.messageOwner?.fwd_from?.date?.toLong() ?: 0
        val day: String = LocaleController.formatDate(date)
        val time: String = LocaleController.getInstance().formatterDay.format(date * 1000)
        return if (!NaConfig.dateOfForwardedMsg.Bool() || date == 0L) {
            orig
        } else {
            if (day == time) {
                "$orig · $day"
            } else "$orig · $day $time"
        }
    }

    fun zalgoFilter(
        text: String
    ): String {
        return zalgoFilter(text as CharSequence).toString()
    }

    fun zalgoFilter(
        text: CharSequence?
    ): CharSequence {
        return if (text == null) {
            ""
        } else if (NaConfig.zalgoFilter.Bool() && text.matches(
                ".*\\p{Mn}{4}.*".toRegex()
            )
        ) {
            text.replace(
                "(?i)([aeiouy]̈)|[̀-ͯ҉]".toRegex(),
                ""
            )
                .replace(
                    "[\\p{Mn}]".toRegex(),
                    ""
                )
        } else {
            text
        }
    }

    @JvmStatic
    fun containsMarkdown(text: CharSequence?): Boolean {
        val newText = AndroidUtilities.getTrimmedString(text)
        val message = arrayOf(AndroidUtilities.getTrimmedString(newText))
        return MediaDataController.getInstance(UserConfig.selectedAccount)
            .getEntities(message, true).size > 0
    }

    @JvmStatic
    fun canSendAsDice(text: String, parentFragment: ChatActivity, dialog_id: Long): Boolean {
        var canSendGames = true
        if (DialogObject.isChatDialog(dialog_id)) {
            val chat: Chat = parentFragment.messagesController.getChat(-dialog_id)
            canSendGames = ChatObject.canSendStickers(chat)
        }
        return canSendGames && parentFragment.messagesController.diceEmojies.contains(
            text.replace(
                "\ufe0f",
                ""
            )
        )
    }

    private fun formatTime(timestamp: Int): String {
        return LocaleController.formatString(R.string.formatDateAtTime, LocaleController.getInstance().formatterYear.format(Date(timestamp * 1000L)), LocaleController.getInstance().formatterDay.format(Date(timestamp * 1000L)))
    }

    fun getTimeHintText(messageObject: MessageObject): CharSequence {
        val text = SpannableStringBuilder()
        if (spannedStrings[3] == null) {
            spannedStrings[3] = SpannableStringBuilder("\u200B")
            spannedStrings[3]?.setSpan(ColoredImageSpan(Theme.chat_timeHintSentDrawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append(spannedStrings[3])
        text.append(' ')
        text.append(formatTime(messageObject.messageOwner.date))
        if (messageObject.messageOwner.edit_date != 0) {
            text.append("\n")
            if (spannedStrings[1] == null) {
                spannedStrings[1] = SpannableStringBuilder("\u200B")
                spannedStrings[1]?.setSpan(ColoredImageSpan(Theme.chat_editDrawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text.append(spannedStrings[1])
            text.append(' ')
            text.append(formatTime(messageObject.messageOwner.edit_date))
        }
        if (messageObject.messageOwner.fwd_from != null && messageObject.messageOwner.fwd_from.date != 0) {
            text.append("\n")
            if (spannedStrings[4] == null) {
                spannedStrings[4] = SpannableStringBuilder("\u200B")
                val span = ColoredImageSpan(Theme.chat_timeHintForwardDrawable)
                span.setSize(AndroidUtilities.dp(12f))
                spannedStrings[4]?.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text.append(spannedStrings[4])
            text.append(' ')
            text.append(formatTime(messageObject.messageOwner.fwd_from.date))
        }
        return text
    }

    private var spoilerChars: CharArray = charArrayOf(
        '⠌', '⡢', '⢑', '⠨', '⠥', '⠮', '⡑'
    )

    fun blurify(text: CharSequence): CharSequence {
        val stringBuilder = StringBuilder(text)
        for (i in text.indices) {
            stringBuilder.setCharAt(i, spoilerChars[i % spoilerChars.size])
        }
        return stringBuilder
    }

    fun blurify(messageObject: MessageObject) {
        if (messageObject.messageOwner == null) {
            return
        }

        if (!TextUtils.isEmpty(messageObject.messageText)) {
            messageObject.messageText = blurify(messageObject.messageText)
        }

        if (!TextUtils.isEmpty(messageObject.messageOwner.message)) {
            messageObject.messageOwner.message = blurify(messageObject.messageOwner.message).toString()
        }

        if (!TextUtils.isEmpty(messageObject.caption)) {
            messageObject.caption = blurify(messageObject.caption)
        }

        if (messageObject.messageOwner.media != null) {
            messageObject.messageOwner.media.spoiler = true
        }
    }
}
