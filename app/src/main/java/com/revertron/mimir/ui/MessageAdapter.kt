package com.revertron.mimir.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.method.MovementMethod
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import android.widget.Toast
import java.util.regex.Pattern
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.FileProvider
import androidx.core.view.updateMargins
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.AudioPlaybackService
import com.revertron.mimir.ConnectionService
import com.revertron.mimir.MSG_TYPE_VIDEO_MESSAGE
import com.revertron.mimir.R
import com.revertron.mimir.isUnsupportedMessageType
import com.revertron.mimir.getAvatarColor
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri
import kotlin.math.abs

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MessageAdapter(
    private val provider: MessageProvider,
    private val chatId: Long,
    private val groupChat: Boolean,
    private val contactName: String,
    private val myName: String,
    private val onClick: View.OnClickListener,
    private val onReplyClick: View.OnClickListener,
    private val onPictureClick: View.OnClickListener,
    private val fontSize: Int,
    private val onAvatarClick: View.OnClickListener? = null,
    private val onReactionsMarkedSeen: (() -> Unit)? = null
): RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    companion object {
        const val TAG = "MessageAdapter"

        // Emoji mapping from identifiers to Unicode characters
        val EMOJI_MAP = mapOf(
            "thumbsup" to "\uD83D\uDC4D",    // 👍
            "heart" to "\u2764\uFE0F",        // ❤️
            "laugh" to "\uD83D\uDE02",        // 😂
            "surprised" to "\uD83D\uDE2E",   // 😮
            "sad" to "\uD83D\uDE22",          // 😢
            "angry" to "\uD83D\uDE20",        // 😠
            "clap" to "\uD83D\uDC4F",         // 👏
            "fire" to "\uD83D\uDD25"          // 🔥
        )

        // Custom URL scheme patterns for unusual protocols
        private val CUSTOM_SCHEMES = arrayOf("mimir", "tcp", "tls", "ws", "wss", "quic")

        // Regex pattern that matches URLs with custom schemes
        // Pattern: (scheme)://[any non-whitespace characters]
        private val CUSTOM_URL_PATTERN = Pattern.compile(
            "\\b(" + CUSTOM_SCHEMES.joinToString("|") + ")://[^\\s]+",
            Pattern.CASE_INSENSITIVE
        )
    }

    /**
     * Custom MovementMethod that handles link clicks while passing
     * non-link touch events to the parent view for natural onClick handling.
     * Clears the pressed state when the user scrolls to prevent stuck highlights.
     */
    private class LinkAndClickMovementMethod : LinkMovementMethod() {
        private var initialX = 0f
        private var initialY = 0f
        private var touchSlop = 0
        private var isMoving = false

        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.action

            // Initialize touch slop on first use
            if (touchSlop == 0) {
                touchSlop = android.view.ViewConfiguration.get(widget.context).scaledTouchSlop / 3
            }

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    // Store initial touch position
                    initialX = event.x
                    initialY = event.y
                    isMoving = false
                    if (widget.parent is View) {
                        val p = widget.parent as View
                        if (p.id == R.id.message) {
                            // Store touch coordinates for later use
                            val currentTag = p.tag
                            if (currentTag is MessageTag) {
                                p.tag = currentTag.copy(
                                    touchX = event.rawX.toInt(),
                                    touchY = event.rawY.toInt()
                                )
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // Check if movement exceeds touch slop
                    val deltaX = abs(event.x - initialX)
                    val deltaY = abs(event.y - initialY)

                    if (deltaX >= touchSlop || deltaY >= touchSlop) {
                        // User is scrolling, clear pressed state on both widget and parent
                        if (!isMoving) {
                            isMoving = true

                            // Clear pressed state on TextView
                            widget.cancelLongPress()
                            widget.isPressed = false
                            widget.isSelected = false
                            widget.refreshDrawableState()

                            // Also clear pressed state on parent view
                            val parent = widget.parent
                            if (parent is View) {
                                parent.cancelLongPress()
                                parent.isPressed = false
                                parent.isSelected = false
                                parent.refreshDrawableState()
                                // Force drawable to jump to current state
                                parent.background?.jumpToCurrentState()
                            }

                            // Send ACTION_CANCEL to properly cancel the touch sequence
                            val cancelEvent = MotionEvent.obtain(event)
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            widget.onTouchEvent(cancelEvent)
                            if (parent is View) {
                                parent.onTouchEvent(cancelEvent)
                            }
                            cancelEvent.recycle()
                        }
                        // Return false to let RecyclerView handle scrolling
                        return false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Touch was cancelled (e.g., parent intercepted), clear state
                    isMoving = false
                    widget.isPressed = false
                    widget.isSelected = false
                    widget.refreshDrawableState()

                    val parent = widget.parent
                    if (parent is View) {
                        parent.isPressed = false
                        parent.isSelected = false
                        parent.refreshDrawableState()
                        parent.background?.jumpToCurrentState()
                    }
                    return false
                }
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                var x = event.x.toInt()
                var y = event.y.toInt()

                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop

                x += widget.scrollX
                y += widget.scrollY

                val layout = widget.layout
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())

                val links = buffer.getSpans(off, off, ClickableSpan::class.java)

                if (links.isNotEmpty()) {
                    // There's a link at this position, handle it normally
                    if (action == MotionEvent.ACTION_UP) {
                        links[0].onClick(widget)
                    }
                    return true
                } else {
                    // No link - pass the touch event to parent view so it can handle onClick naturally
                    val parent = widget.parent
                    if (parent is View) {
                        // Dispatch the event to parent's touch handling
                        return parent.onTouchEvent(event)
                    }
                }
            }

            return super.onTouchEvent(widget, buffer, event)
        }

        companion object {
            private var sInstance: LinkAndClickMovementMethod? = null

            fun getInstance(): MovementMethod {
                if (sInstance == null) {
                    sInstance = LinkAndClickMovementMethod()
                }
                return sInstance!!
            }
        }
    }

    private val timeFormatter = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(DateFormat.SHORT)

    // Audio playback state tracking
    private var currentPlayingMessageId: Long = -1
    private var isAudioPlaying: Boolean = false
    /** Maps guid → (bytesTransferred, totalBytes); totalBytes=0 means indeterminate. */
    private val downloadingProgress = mutableMapOf<Long, Pair<Long, Long>>()
    /** Maps guid → (bytesSent, totalBytes) for outgoing file uploads. */
    private val uploadingProgress = mutableMapOf<Long, Pair<Long, Long>>()
    /** Cache: guid → adapter position, to avoid O(n) scans on every progress tick. */
    private val guidPositionCache = mutableMapOf<Long, Int>()

    private val messageIds = provider.getMessageIds(chatId, groupChat).toMutableList()

    // Cached nicknames and avatars
    private val users: HashMap<Long, Pair<String, Drawable?>?> = HashMap()

    // ---- Inline video playback (single reused player, one expanded tile at a time) ----
    private var recyclerView: RecyclerView? = null
    private var exoPlayer: ExoPlayer? = null
    private var playingHolder: ViewHolder? = null
    private var playingGuid: Long = 0L
    private val videoHandler = Handler(Looper.getMainLooper())
    private var seekRunnable: Runnable? = null
    private val progressRunnable = object : Runnable {
        override fun run() {
            val holder = playingHolder ?: return
            val player = exoPlayer ?: return
            val dur = player.duration
            if (dur > 0) {
                holder.videoProgress.progress = (player.currentPosition * 1000 / dur).toInt()
            }
            videoHandler.postDelayed(this, 200)
        }
    }
    // While a video is expanded, a touch landing outside its panel collapses it.
    private val outsideTapInterceptor = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return false
            val holder = playingHolder ?: return false
            val panel = holder.videoPanel
            val rect = Rect(0, 0, panel.width, panel.height)
            rv.offsetDescendantRectToMyCoords(panel, rect)
            if (!rect.contains(e.x.toInt(), e.y.toInt())) {
                collapseVideo(animate = true)
                return true  // consume so the tap doesn't also open a context menu
            }
            return false
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    class ViewHolder(view: View, hasAvatar: Boolean): RecyclerView.ViewHolder(view) {
        val avatar: AppCompatImageView? = if (hasAvatar) view.findViewById(R.id.avatar) else null
        val name: AppCompatTextView = view.findViewById(R.id.name)
        val message: AppCompatTextView = view.findViewById(R.id.text)
        val picture: AppCompatImageView = view.findViewById(R.id.picture)
        val picturePanel: FrameLayout = view.findViewById(R.id.picture_panel)
        val filePanel: View = view.findViewById(R.id.file_panel)
        val fileIconContainer: View = view.findViewById(R.id.file_icon_container)
        val fileIcon: AppCompatImageView = view.findViewById(R.id.file_icon)
        val fileName: AppCompatTextView = view.findViewById(R.id.file_name)
        val fileSize: AppCompatTextView = view.findViewById(R.id.file_size)
        val fileProgress: CircularProgressIndicator = view.findViewById(R.id.file_progress)
        val videoPanel: View = view.findViewById(R.id.video_panel)
        val videoPoster: AppCompatImageView = view.findViewById(R.id.video_poster)
        val videoPlayBadge: AppCompatImageView = view.findViewById(R.id.video_play_badge)
        val videoPlayer: PlayerView = view.findViewById(R.id.video_player)
        val videoProgress: ProgressBar = view.findViewById(R.id.video_progress)
        val videoDuration: AppCompatTextView = view.findViewById(R.id.video_duration)
        val time: AppCompatTextView = view.findViewById(R.id.time)
        val sent: AppCompatImageView = view.findViewById(R.id.status_image)
        val replyToName: AppCompatTextView = view.findViewById(R.id.reply_contact_name)
        val replyToText: AppCompatTextView = view.findViewById(R.id.reply_text)
        val replyToPanel: View = view.findViewById(R.id.reply_panel)
        val reactionsRow: LinearLayout = view.findViewById(R.id.reactions_row)

        // The bubble container — used to read its margins/padding when sizing the
        // expanded video to the bubble's content width.
        val bubble: View = view.findViewById(R.id.message)
    }

    /**
     * Applies custom link patterns to the TextView for unusual URL schemes.
     * Handles both standard web URLs and custom schemes like mimir://, tcp://, tls://, ws://, wss://
     * Uses custom MovementMethod to allow clicking on non-link text to trigger the popup menu.
     */
    private fun applyCustomLinks(textView: AppCompatTextView, text: CharSequence, context: Context) {
        val spannable = SpannableString(text)

        // First apply standard web URL linkification
        Linkify.addLinks(spannable, Linkify.WEB_URLS)

        // Then add custom scheme patterns
        val matcher = CUSTOM_URL_PATTERN.matcher(text)

        while (matcher.find()) {
            val url = matcher.group()
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    handleCustomUrl(context, url)
                }
            }
            spannable.setSpan(
                clickableSpan,
                matcher.start(),
                matcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        textView.text = spannable

        // Use custom MovementMethod that handles both link clicks and delegates
        // non-link clicks to the parent view (R.id.message) for the popup menu
        textView.movementMethod = LinkAndClickMovementMethod.getInstance()
    }

    /**
     * Handles clicks on custom URL schemes.
     * Opens the URL using an ACTION_VIEW intent.
     */
    private fun handleCustomUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Try to start the activity
            try {
                context.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                // No app can handle this scheme, show a toast
                Toast.makeText(context, context.getString(R.string.no_app_found_to_open, url), Toast.LENGTH_SHORT).show()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Url", url)
                clipboard.setPrimaryClip(clip)
                Log.w(TAG, "No app to handle URL: $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening custom URL: $url", e)
            Toast.makeText(context, "Cannot open URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        recyclerView.addOnItemTouchListener(outsideTapInterceptor)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerView.removeOnItemTouchListener(outsideTapInterceptor)
        if (this.recyclerView === recyclerView) this.recyclerView = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // A playing tile must never survive recycling — tear it down immediately.
        if (holder === playingHolder) {
            collapseVideo(animate = false)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 0) {
            R.layout.message_incoming_layout
        } else {
            R.layout.message_outgoing_layout
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)

        // Use touch listener to capture coordinates for popup menu
        val onTouchListener: (View, MotionEvent) -> Boolean = { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val view = if (v.id == R.id.message) {
                        v
                    } else {
                        v.findViewById(R.id.message)
                    }
                    // Store touch coordinates for later use
                    val currentTag = view.tag
                    if (currentTag is MessageTag) {
                        v.tag = currentTag.copy(
                            touchX = event.rawX.toInt(),
                            touchY = event.rawY.toInt()
                        )
                    }
                    false
                }

                else -> false
            }
        }
        view.setOnClickListener(onClick)
        view.setOnTouchListener(onTouchListener)
        view.findViewById<View>(R.id.message).setOnTouchListener(onTouchListener)

        view.findViewById<View>(R.id.reply_panel).setOnClickListener(onReplyClick)
        view.findViewById<View>(R.id.picture).setOnClickListener(onPictureClick)
        if (!groupChat) {
            view.findViewById<View>(R.id.avatar)?.visibility = View.GONE
        } else if (onAvatarClick != null) {
            // Set click listener for avatar in group chats
            view.findViewById<View>(R.id.avatar)?.setOnClickListener(onAvatarClick)
        }

        return ViewHolder(view, viewType == 0 && groupChat)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = provider.getMessage(chatId, messageIds[position].first, groupChat) ?: return

        holder.message.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
        holder.replyToText.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSize - 2).toFloat())

        if (groupChat) {
            if (message.incoming) {
                holder.avatar?.visibility = View.VISIBLE
                val user = users.getOrPut(message.contact, {
                    provider.getMemberDisplayInfo(message.contact, chatId, 48, 6)
                })
                val pubKey = provider.getMemberPubkey(message.contact, chatId)
                val avatarColor = if (pubKey != null) {
                    getAvatarColor(pubKey)
                } else {
                    0x808080
                }
                if (user != null) {
                    holder.name.text = user.first
                    holder.name.visibility = View.VISIBLE

                    if (user.second != null) {
                        holder.avatar?.clearColorFilter()
                        holder.avatar?.setImageDrawable(user.second)
                    } else {
                        // Use default avatar with color based on pubkey
                        holder.avatar?.setImageResource(R.drawable.button_rounded_white)
                        pubKey?.let {
                            holder.avatar?.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
                        }
                    }
                    // Apply the same color to the nickname
                    holder.name.setTextColor(avatarColor)

                    // Set the contact ID as tag for onClick handler
                    holder.avatar?.tag = message.contact
                } else if (message.type < 1000) {
                    holder.name.text = holder.name.context.getString(R.string.unknown_nickname)
                    // Use default avatar with color based on pubkey
                    holder.avatar?.setImageResource(R.drawable.button_rounded_white)
                    holder.avatar?.let {
                        holder.avatar?.setColorFilter(avatarColor, PorterDuff.Mode.MULTIPLY)
                    }
                } else {
                    holder.name.visibility = View.GONE
                    holder.avatar?.visibility = View.GONE
                }
            } else {
                holder.name.visibility = View.GONE
                holder.name.text = ""
            }
        } else {
            holder.name.visibility = View.GONE
            holder.avatar?.visibility = View.GONE
        }

        // Forward-compat: a stored message type this client version doesn't
        // understand is rendered as a distinct "unsupported" panel (see the
        // `when` below) rather than dumping its payload as raw text.
        val unsupported = isUnsupportedMessageType(message.type)

        // Get message text (system messages need provider-side parsing + member lookup).
        // msgType=1000 means "system message" only in group chats; in 1:1 chats it's plain text.
        if (unsupported) {
            holder.message.text = ""
            holder.message.visibility = View.GONE
        } else {
            val messageText = if (groupChat && message.type == 1000 && message.data != null) {
                provider.formatSystemMessage(holder.itemView.context, chatId, message.data)
                    ?: holder.itemView.context.getString(R.string.system_message)
            } else {
                message.getText(holder.itemView.context)
            }

            // Apply link detection based on message type
            if (message.type != 3) {
                // For non-file messages, apply custom link detection (web URLs + custom schemes)
                applyCustomLinks(holder.message, messageText, holder.itemView.context)
            } else {
                // For file attachments, disable autoLink to prevent filename from being interpreted as a URL
                holder.message.autoLinkMask = 0
                holder.message.text = messageText
            }

            holder.message.visibility = View.VISIBLE
        }
        holder.message.setCompoundDrawables(null, null, null ,null)
        holder.sent.visibility = if (message.incoming || message.type == 1000) View.GONE else View.VISIBLE
        holder.videoPanel.visibility = View.GONE
        // A recycled holder may carry leftover inline-video expansion state — reset it.
        resetVideoExpansionState(holder)

        when (message.type) {
            1 -> {
                // Image message - may need download first
                holder.filePanel.visibility = View.GONE
                if (message.data != null) {
                    val string = String(message.data)
                    val json = JSONObject(string)
                    val name = json.getString("name")
                    val cachePath = File(holder.itemView.context.cacheDir, "files")
                    val filePath = File(holder.itemView.context.filesDir, "files")
                    val cacheFile = File(cachePath, name)
                    val imageFile = File(filePath, name)
                    if (cacheFile.exists()) {
                        val uri: Uri = Uri.fromFile(cacheFile)
                        holder.picture.setImageURI(uri)
                        holder.picture.tag = Uri.fromFile(imageFile)
                        holder.picturePanel.visibility = View.VISIBLE
                    } else if (imageFile.exists()) {
                        val uri: Uri = Uri.fromFile(imageFile)
                        holder.picture.setImageURI(uri)
                        holder.picture.tag = uri
                        holder.picturePanel.visibility = View.VISIBLE
                    } else {
                        // File not downloaded yet — show download prompt in file panel
                        holder.picture.setImageDrawable(null)
                        holder.picturePanel.visibility = View.GONE
                        val size = json.optLong("size", 0L)
                        val isDownloading = downloadingProgress.containsKey(message.guid)
                        if (isDownloading) {
                            holder.fileIcon.setImageResource(R.drawable.ic_folder_download_outline)
                            holder.fileName.text = holder.itemView.context.getString(R.string.downloading_file)
                            bindFileProgress(holder, message.guid, size)
                            holder.filePanel.setOnClickListener(null)
                        } else {
                            holder.fileIcon.setImageResource(R.drawable.ic_folder_download_outline)
                            holder.fileName.text = holder.itemView.context.getString(R.string.download_file)
                            holder.fileSize.text = formatFileSize(size)
                            holder.fileProgress.visibility = View.GONE
                            holder.fileIcon.visibility = View.VISIBLE
                            holder.filePanel.setOnClickListener {
                                downloadingProgress[message.guid] = Pair(0L, 0L)
                                notifyItemChanged(holder.bindingAdapterPosition)
                                requestFileDownload(holder.itemView.context, message.guid, message.data)
                            }
                        }
                        holder.filePanel.visibility = View.VISIBLE
                    }
                }
            }
            2 -> {
                // Audio call info
                holder.filePanel.visibility = View.VISIBLE
                holder.fileProgress.visibility = View.GONE
                holder.fileIcon.visibility = View.VISIBLE
                holder.fileIcon.setImageResource(R.drawable.ic_phone_outline_themed)
                holder.fileName.text = if (message.incoming) {
                    holder.fileName.context.getString(R.string.audio_call_incoming)
                } else {
                    holder.fileName.context.getString(R.string.audio_call_outgoing)
                }
                holder.fileSize.text = holder.message.text
                holder.message.visibility = View.GONE
                holder.sent.visibility = View.GONE
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            3 -> {
                // File attachment - show file panel with icon and info
                if (message.data != null) {
                    val string = String(message.data)
                    try {
                        val json = JSONObject(string)
                        val name = json.getString("name")
                        val original = json.optString("originalName", name)
                        val size = json.optLong("size", 0L)
                        val filePath = File(holder.itemView.context.filesDir, "files")
                        val file = File(filePath, name)

                        // Get file icon based on MIME type
                        val mimeType = json.optString("mimeType", "application/octet-stream")
                        val isAudio = mimeType.startsWith("audio/")

                        // Set file icon - for audio, show play/pause based on state
                        if (isAudio) {
                            val isThisPlaying = currentPlayingMessageId == message.id && isAudioPlaying
                            if (isThisPlaying) {
                                holder.fileIcon.setImageResource(R.drawable.ic_pause_outline)
                            } else {
                                holder.fileIcon.setImageResource(R.drawable.ic_play_outline)
                            }
                        } else {
                            holder.fileIcon.setImageResource(getFileIconForMimeType(mimeType))
                        }

                        // Set file name and size
                        holder.fileName.text = original
                        holder.fileSize.text = formatFileSize(size)

                        // Show file panel, hide text
                        holder.filePanel.visibility = View.VISIBLE
                        if (holder.message.text.isEmpty()) {
                            holder.message.visibility = View.GONE
                        }

                        // Set click listener
                        val uploadProgress = uploadingProgress[message.guid]
                        if (uploadProgress != null) {
                            bindFileProgress(holder, message.guid, size, upload = true)
                        } else {
                            holder.fileProgress.visibility = View.GONE
                            holder.fileIcon.visibility = View.VISIBLE
                        }
                        if (file.exists()) {
                            if (isAudio) {
                                // Audio file - toggle playback in AudioPlaybackService
                                holder.fileIconContainer.setOnClickListener {
                                    val context = holder.itemView.context
                                    val intent = Intent(context, AudioPlaybackService::class.java).apply {
                                        action = AudioPlaybackService.ACTION_TOGGLE
                                        putExtra(AudioPlaybackService.EXTRA_FILE_PATH, file.absolutePath)
                                        putExtra(AudioPlaybackService.EXTRA_FILE_NAME, original)
                                        putExtra(AudioPlaybackService.EXTRA_MESSAGE_ID, message.id)
                                        putExtra(AudioPlaybackService.EXTRA_CHAT_ID, chatId)
                                        putExtra(AudioPlaybackService.EXTRA_IS_GROUP_CHAT, groupChat)
                                    }
                                    context.startService(intent)
                                }
                            }
                            // Every file - open with external app
                            holder.filePanel.setOnClickListener {
                                openFile(holder.itemView.context, file, mimeType)
                            }
                        } else if (message.incoming) {
                            // File not downloaded yet — show download icon and allow manual download
                            val isDownloading = downloadingProgress.containsKey(message.guid)
                            if (isDownloading) {
                                holder.fileIcon.setImageResource(R.drawable.ic_folder_download_outline)
                                bindFileProgress(holder, message.guid, size)
                                holder.filePanel.setOnClickListener(null)
                            } else {
                                holder.fileIcon.setImageResource(R.drawable.ic_folder_download_outline)
                                holder.fileProgress.visibility = View.GONE
                                holder.fileIcon.visibility = View.VISIBLE
                                holder.filePanel.setOnClickListener {
                                    downloadingProgress[message.guid] = Pair(0L, 0L)
                                    notifyItemChanged(holder.bindingAdapterPosition)
                                    requestFileDownload(holder.itemView.context, message.guid, message.data)
                                }
                            }
                        }
                    } catch (_: JSONException) {
                        val text = "Unable to format message of type ${message.type}:\n" + String(message.data)
                        holder.message.text = text
                        holder.picture.setImageDrawable(null)
                        holder.picturePanel.visibility = View.GONE
                    }
                }
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            MSG_TYPE_VIDEO_MESSAGE -> {
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
                bindVideoMessage(holder, message)
            }
            1000 -> {
                holder.filePanel.visibility = View.GONE
                holder.picture.setImageDrawable(null)
                holder.picturePanel.visibility = View.GONE
            }
            else -> {
                if (unsupported) {
                    // Forward-compat placeholder for a message type this client can't render.
                    bindUnsupportedMessage(holder)
                } else {
                    // Plain text (type 0) and other known text-like types.
                    holder.filePanel.visibility = View.GONE
                    holder.picture.setImageDrawable(null)
                    holder.picturePanel.visibility = View.GONE
                }
            }
        }
        // System messages (type 1000) are now handled by getText() method above
        // which provides comprehensive event descriptions

        // Display reactions for this message
        displayReactions(holder, message)

        holder.time.text = formatTime(message.time)
        // Store message metadata using MessageTag
        holder.itemView.findViewById<View>(R.id.message).tag = MessageTag(message.id, message.guid, message.type)
        holder.sent.tag = message.delivered

        // Disable click listener for system messages (type 1000)
        if (message.type == 1000) {
            holder.itemView.findViewById<View>(R.id.message).setOnClickListener(null)
            holder.itemView.findViewById<View>(R.id.message).isClickable = false
        } else {
            holder.itemView.findViewById<View>(R.id.message).setOnClickListener(onClick)
            holder.itemView.findViewById<View>(R.id.message).isClickable = true
        }

        if (message.replyTo != 0L) {
            val replyToMessage = provider.getMessageByGuid(chatId, message.replyTo, groupChat)
            if (replyToMessage != null) {
                holder.replyToPanel.visibility = View.VISIBLE
                holder.replyToPanel.tag = replyToMessage.id

                val unknown = holder.message.context.getString(R.string.unknown_nickname)

                val authorName = if (groupChat) {
                    if (replyToMessage.incoming) {
                        val user = provider.getMemberDisplayInfo(replyToMessage.contact, chatId, 48, 6)
                        user?.first ?: unknown
                    } else {
                        myName
                    }
                } else {
                    if (replyToMessage.incoming) contactName else myName
                }

                val replyText = replyToMessage.getText(holder.itemView.context).replace('\n', ' ')

                holder.replyToName.text = authorName
                holder.replyToText.text = replyText
            } else {
                // Message may be deleted or GUID mismatch
                Log.w("MessageAdapter", "Reply lookup FAILED for GUID ${message.replyTo} - original message not found in database")
                holder.replyToPanel.visibility = View.GONE
                holder.replyToName.text = ""
                holder.replyToText.text = ""
            }
        } else {
            holder.replyToPanel.visibility = View.GONE
            holder.replyToName.text = ""
            holder.replyToText.text = ""
        }

        if (message.delivered) {
            holder.sent.setImageResource(R.drawable.ic_message_delivered)
        } else {
            holder.sent.setImageResource(R.drawable.ic_message_not_sent)
        }
        // Apply top margin to the message view (not itemView) for the first message
        val messageView = holder.itemView.findViewById<View>(R.id.message)
        val messageLayoutParams = messageView.layoutParams as ViewGroup.MarginLayoutParams
        if (position == 0) {
            messageLayoutParams.updateMargins(top = messageLayoutParams.bottomMargin)
        } else {
            messageLayoutParams.updateMargins(top = 0)
        }
        provider.setMessageRead(chatId, message.id, groupChat)

        // Mark reactions targeting this outgoing message as seen when it's visible.
        if (!message.incoming && message.guid != 0L) {
            val marked = provider.markReactionsAsSeenForMessage(chatId, message.guid, groupChat)
            if (marked > 0) onReactionsMarkedSeen?.invoke()
        }
    }

    /**
     * Displays reactions for a message as emoji chips in the reactions row.
     */
    private fun displayReactions(holder: ViewHolder, message: DisplayMessage) {
        val row = holder.reactionsRow
        row.removeAllViews()
        val reactions = provider.getReactionsForMessage(chatId, message.guid, groupChat)
        if (reactions.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val marginEnd = (4 * density).toInt()
        for (reaction in reactions) {
            val chip = AppCompatTextView(context).apply {
                text = if (reaction.count > 1) "${reaction.emoji} ${reaction.count}" else reaction.emoji
                textSize = 13f
                setBackgroundResource(
                    if (reaction.userReacted) R.drawable.reaction_background_selected
                    else R.drawable.reaction_background
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = marginEnd
                layoutParams = lp
            }
            row.addView(chip)
        }
    }

    private fun formatTime(time: Long): String {
        val date = Date(time)
        val diff = Date().time - date.time
        return if (diff > 86400 * 1000) {
            "${dateFormatter.format(date)} ${timeFormatter.format(date)}"
        } else {
            timeFormatter.format(date)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    /**
     * Renders a message whose stored type this client version doesn't understand.
     * Reuses the file-panel chrome (icon + bold title + muted subtitle) so the
     * placeholder is visually distinct from a normal text bubble and can't be
     * spoofed by sending the same words as plain text. The raw payload stays in
     * storage, so the message renders correctly once Mimir is updated.
     */
    private fun bindUnsupportedMessage(holder: ViewHolder) {
        val context = holder.itemView.context
        holder.message.visibility = View.GONE
        holder.picture.setImageDrawable(null)
        holder.picturePanel.visibility = View.GONE
        holder.videoPanel.visibility = View.GONE
        holder.fileProgress.visibility = View.GONE
        holder.fileIcon.visibility = View.VISIBLE
        holder.fileIcon.setImageResource(R.drawable.ic_message_alert_outline)
        holder.fileName.text = context.getString(R.string.unsupported_message_title)
        holder.fileSize.text = context.getString(R.string.unsupported_message_desc)
        holder.fileIconContainer.setOnClickListener(null)
        holder.filePanel.setOnClickListener(null)
        holder.filePanel.visibility = View.VISIBLE
    }

    override fun getItemViewType(position: Int): Int {
        return if (messageIds[position].second) 0 else 1
    }

    override fun getItemCount(): Int {
        return messageIds.size
    }

    fun addMessageId(messageId: Long, incoming: Boolean) {
        messageIds.add(messageId to incoming)
        guidPositionCache.clear()
        notifyItemInserted(messageIds.size - 1)
    }

    fun deleteMessageId(messageId: Long) {
        for ((index, message) in messageIds.withIndex()) {
            if (message.first == messageId) {
                messageIds.removeAt(index)
                guidPositionCache.clear()
                notifyItemRemoved(index)
                break
            }
        }
    }

    /**
     * Rebinds the row for the message with the given GUID. Used when reactions change:
     * invalidates the reactions cache so the row re-aggregates on next bind.
     */
    fun notifyMessageChanged(targetGuid: Long) {
        provider.invalidateReactionsCache(chatId, groupChat)
        val position = getMessageGuidPosition(targetGuid)
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    fun clearAllMessages() {
        val oldSize = messageIds.size
        messageIds.clear()
        guidPositionCache.clear()
        notifyItemRangeRemoved(0, oldSize)
    }

    fun setMessageDelivered(id: Long, delivered: Boolean) {
        for ((index, message) in messageIds.withIndex()) {
            if (message.first == id) {
                notifyItemChanged(index)
                break
            }
        }
    }

    fun getMessageIdPosition(id: Long): Int {
        for ((index, message) in messageIds.withIndex()) {
            if (message.first == id) {
                return index
            }
        }
        return -1
    }

    fun getMessageGuidPosition(guid: Long): Int {
        // Look up the message ID from GUID
        val messageId = provider.getMessageByGuid(chatId, guid, groupChat)?.id ?: return -1

        // Find position in messageIds list
        for ((index, message) in messageIds.withIndex()) {
            if (message.first == messageId) {
                return index
            }
        }
        return -1
    }

    /**
     * Clears the cached member info, forcing it to be reloaded from storage.
     * This should be called when member info might have been updated (e.g., avatar changes).
     */
    fun refreshMemberCache() {
        users.clear()
        notifyDataSetChanged()
    }

    /**
     * Refreshes cached info for a specific member by their ID.
     */
    fun refreshMember(memberId: Long) {
        users.remove(memberId)
        // Find and refresh all messages from this member
        for ((index, message) in messageIds.withIndex()) {
            val msg = provider.getMessage(chatId, message.first, groupChat)
            if (msg?.contact == memberId) {
                notifyItemChanged(index)
            }
        }
    }

    /**
     * Updates audio playback state and refreshes affected message items.
     */
    fun updateAudioPlaybackState(isPlaying: Boolean, messageId: Long) {
        val previousMessageId = currentPlayingMessageId
        val wasPlaying = isAudioPlaying

        currentPlayingMessageId = messageId
        isAudioPlaying = isPlaying

        // Refresh the previously playing message if it changed
        if (previousMessageId != -1L && previousMessageId != messageId) {
            val position = getMessageIdPosition(previousMessageId)
            if (position >= 0) {
                notifyItemChanged(position)
            }
        }

        // Refresh the current message
        if (messageId != -1L) {
            val position = getMessageIdPosition(messageId)
            if (position >= 0) {
                notifyItemChanged(position)
            }
        }
    }

    fun markFileDownloading(guid: Long) {
        downloadingProgress[guid] = Pair(0L, 0L)
        notifyItemChangedByGuid(guid)
    }

    fun markFileDownloaded(guid: Long) {
        downloadingProgress.remove(guid)
        guidPositionCache.remove(guid)
        notifyItemChangedByGuid(guid)
    }

    /** Clears any active "Downloading…" state (e.g. the peer disconnected and
     *  in-flight downloads can no longer complete) so each row rebinds to its
     *  download button for a retry. */
    fun resetActiveDownloads() {
        if (downloadingProgress.isEmpty()) return
        val guids = downloadingProgress.keys.toList()
        downloadingProgress.clear()
        for (g in guids) notifyItemChangedByGuid(g)
    }

    private fun notifyItemChangedByGuid(guid: Long) {
        val cached = guidPositionCache[guid]
        if (cached != null && cached in messageIds.indices) {
            notifyItemChanged(cached)
            return
        }
        for (index in messageIds.indices.reversed()) {
            val msg = provider.getMessage(chatId, messageIds[index].first, groupChat) ?: continue
            if (msg.guid == guid) {
                guidPositionCache[guid] = index
                notifyItemChanged(index)
                return
            }
        }
    }

    fun updateFileProgress(guid: Long, bytesTransferred: Long, totalBytes: Long, isUpload: Boolean) {
        val map = if (isUpload) uploadingProgress else downloadingProgress
        if (bytesTransferred >= totalBytes && totalBytes > 0) {
            // Transfer complete — remove tracking
            map.remove(guid)
            guidPositionCache.remove(guid)
        } else {
            map[guid] = Pair(bytesTransferred, totalBytes)
        }

        // Try cached position first
        val cached = guidPositionCache[guid]
        if (cached != null && cached in messageIds.indices) {
            notifyItemChanged(cached)
            return
        }

        // Cache miss — scan by guid
        for (index in messageIds.indices.reversed()) {
            val msg = provider.getMessage(chatId, messageIds[index].first, groupChat) ?: continue
            if (msg.guid == guid) {
                guidPositionCache[guid] = index
                notifyItemChanged(index)
                return
            }
        }
    }

    fun markFileUploaded(guid: Long) {
        uploadingProgress.remove(guid)
        guidPositionCache.remove(guid)
        notifyItemChangedByGuid(guid)
    }

    /**
     * Binds the progress bar and file size text for an active file transfer.
     */
    private fun bindFileProgress(holder: ViewHolder, guid: Long, totalSize: Long, upload: Boolean = false) {
        val map = if (upload) uploadingProgress else downloadingProgress
        val (transferred, total) = map[guid] ?: Pair(0L, 0L)
        holder.fileIcon.visibility = View.GONE
        holder.fileProgress.visibility = View.VISIBLE
        if (total > 0 && transferred > 0) {
            val percent = (transferred * 100 / total).toInt()
            holder.fileProgress.setProgressCompat(percent, true)
            holder.fileSize.text = "${formatFileSize(transferred)} / ${formatFileSize(totalSize)}"
        } else {
            holder.fileProgress.setProgressCompat(0, false)
            holder.fileSize.text = formatFileSize(totalSize)
        }
    }

    private fun bindVideoMessage(holder: ViewHolder, message: DisplayMessage) {
        val data = message.data ?: run {
            holder.videoPanel.visibility = View.GONE
            holder.filePanel.visibility = View.GONE
            return
        }
        val context = holder.itemView.context
        try {
            val json = JSONObject(String(data))
            val name = json.getString("name")
            val size = json.optLong("size", 0L)
            val caption = json.optString("text", "")
            val file = File(File(context.filesDir, "files"), name)

            // Only show the text line when there's a real caption (getText()
            // otherwise returns the "Video message" label, which we don't want
            // duplicated under the tile).
            holder.message.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE

            if (file.exists()) {
                holder.filePanel.visibility = View.GONE
                holder.videoPanel.visibility = View.VISIBLE
                holder.videoDuration.text = ""
                bindVideoThumb(holder, file, name)
                holder.videoPanel.setOnClickListener {
                    expandAndPlay(holder, file, message.guid)
                }
            } else if (message.incoming) {
                // Not downloaded yet — show a download prompt in the file panel.
                holder.videoPanel.visibility = View.GONE
                val isDownloading = downloadingProgress.containsKey(message.guid)
                holder.fileIcon.setImageResource(R.drawable.ic_folder_download_outline)
                if (isDownloading) {
                    holder.fileName.text = context.getString(R.string.downloading_file)
                    bindFileProgress(holder, message.guid, size)
                    holder.filePanel.setOnClickListener(null)
                } else {
                    holder.fileName.text = context.getString(R.string.video_message)
                    holder.fileSize.text = formatFileSize(size)
                    holder.fileProgress.visibility = View.GONE
                    holder.fileIcon.visibility = View.VISIBLE
                    holder.filePanel.setOnClickListener {
                        downloadingProgress[message.guid] = Pair(0L, 0L)
                        notifyItemChanged(holder.bindingAdapterPosition)
                        requestFileDownload(context, message.guid, message.data)
                    }
                }
                holder.filePanel.visibility = View.VISIBLE
            } else {
                holder.videoPanel.visibility = View.GONE
                holder.filePanel.visibility = View.GONE
            }
        } catch (_: JSONException) {
            holder.videoPanel.visibility = View.GONE
            holder.filePanel.visibility = View.GONE
        }
    }

    /** Extracts the poster frame + duration off the UI thread, caching the
     *  poster JPEG under cacheDir/files so later binds are instant. */
    private fun bindVideoThumb(holder: ViewHolder, file: File, name: String) {
        val context = holder.itemView.context
        val cacheDir = File(context.cacheDir, "files").apply { mkdirs() }
        val poster = File(cacheDir, "vthumb_$name.jpg")
        if (poster.exists()) {
            holder.videoPoster.setImageURI(Uri.fromFile(poster))
        } else {
            holder.videoPoster.setImageDrawable(null)
        }
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var frame: Bitmap? = null
                if (!poster.exists()) {
                    frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                retriever.release()
                if (frame != null) {
                    poster.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                }
                holder.itemView.post {
                    if (poster.exists()) holder.videoPoster.setImageURI(Uri.fromFile(poster))
                    val s = durMs / 1000
                    holder.videoDuration.text = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
                }
            } catch (e: Exception) {
                Log.w("MessageAdapter", "Video thumb failed: ${e.message}")
            }
        }.start()
    }

    // ----------------------------- Inline video playback -----------------------------

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun getOrCreatePlayer(context: Context): ExoPlayer {
        exoPlayer?.let { return it }
        val player = ExoPlayer.Builder(context.applicationContext).build()
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) collapseVideo(animate = true)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Show the play badge whenever playback is not advancing (paused).
                val holder = playingHolder ?: return
                holder.videoPlayBadge.visibility = if (isPlaying) View.GONE else View.VISIBLE
            }
        })
        exoPlayer = player
        return player
    }

    /** Largest square that fits inside the message bubble's content area: the column
     *  width minus the avatar, bubble margins and bubble padding (capped to the visible
     *  height in landscape). Keeps the normal bubble (rounded background + tail). */
    private fun expandedVideoSize(holder: ViewHolder): Int {
        val context = holder.itemView.context
        val rv = recyclerView
        val w = if (rv == null || rv.width == 0) context.resources.displayMetrics.widthPixels else rv.width
        val avatar = holder.avatar
        val avatarSpace = if (avatar != null && avatar.visibility == View.VISIBLE) {
            val alp = avatar.layoutParams as ViewGroup.MarginLayoutParams
            avatar.width.coerceAtLeast(dp(context, 48f)) + alp.leftMargin + alp.rightMargin
        } else 0
        val blp = holder.bubble.layoutParams as ViewGroup.MarginLayoutParams
        var target = w - avatarSpace - blp.marginStart - blp.marginEnd -
                holder.bubble.paddingStart - holder.bubble.paddingEnd
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            val h = if (rv == null || rv.height == 0) context.resources.displayMetrics.heightPixels else rv.height
            // Leave room for the bubble's name/time rows and vertical padding.
            target = minOf(target, h - dp(context, 96f))
        }
        return target.coerceAtLeast(dp(context, 220f))
    }

    /** Enlarges the tapped tile within its bubble and starts playing inline. The bubble
     *  (rounded background + tail) and the video's rounded-corner mask are preserved.
     *  Only one tile plays at a time. */
    private fun expandAndPlay(holder: ViewHolder, file: File, guid: Long) {
        if (playingHolder != null && playingHolder !== holder) collapseVideo(animate = false)
        if (playingHolder === holder) return

        val context = holder.itemView.context
        playingHolder = holder
        playingGuid = guid
        val target = expandedVideoSize(holder)

        // Swap the tile from poster to player (bubble chrome is left untouched).
        holder.videoPoster.visibility = View.GONE
        holder.videoPlayBadge.visibility = View.GONE
        holder.videoDuration.visibility = View.GONE
        holder.videoPlayer.visibility = View.VISIBLE
        holder.videoProgress.visibility = View.VISIBLE
        holder.videoProgress.progress = 0

        val player = getOrCreatePlayer(context)
        holder.videoPlayer.player = player
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.playWhenReady = true
        player.prepare()

        installVideoTouchListener(holder)
        animateVideoPanel(holder, target) {
            // Nudge the now-enlarged bubble fully into view (minimal scroll).
            val v = holder.videoPanel
            v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), false)
        }
        videoHandler.removeCallbacks(progressRunnable)
        videoHandler.post(progressRunnable)
    }

    /** Collapses the currently expanded tile back to the 220dp square. */
    private fun collapseVideo(animate: Boolean) {
        val holder = playingHolder ?: return
        playingHolder = null
        playingGuid = 0L

        videoHandler.removeCallbacks(progressRunnable)
        cancelSeekRepeat()
        holder.videoPanel.setOnTouchListener(null)

        exoPlayer?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        // Swap back to the poster immediately so the shrink animates the tile look.
        holder.videoPlayer.player = null
        holder.videoPlayer.visibility = View.GONE
        holder.videoProgress.visibility = View.GONE
        holder.videoPoster.visibility = View.VISIBLE
        holder.videoPlayBadge.visibility = View.VISIBLE
        holder.videoDuration.visibility = View.VISIBLE

        val collapsed = dp(holder.itemView.context, 220f)
        if (animate && holder.videoPanel.height > collapsed) {
            animateVideoPanel(holder, collapsed)
        } else {
            setVideoPanelSize(holder, collapsed)
        }
    }

    /** Restores a tile to its collapsed poster state (idempotent; for recycled holders). */
    private fun resetVideoExpansionState(holder: ViewHolder) {
        setVideoPanelSize(holder, dp(holder.itemView.context, 220f))
        holder.videoPlayer.player = null
        holder.videoPlayer.visibility = View.GONE
        holder.videoProgress.visibility = View.GONE
        holder.videoPoster.visibility = View.VISIBLE
        holder.videoPlayBadge.visibility = View.VISIBLE
        holder.videoDuration.visibility = View.VISIBLE
    }

    private fun setVideoPanelSize(holder: ViewHolder, size: Int) {
        val lp = holder.videoPanel.layoutParams
        lp.width = size
        lp.height = size
        holder.videoPanel.layoutParams = lp
    }

    private fun animateVideoPanel(holder: ViewHolder, target: Int, onEnd: (() -> Unit)? = null) {
        val start = holder.videoPanel.height.takeIf { it > 0 } ?: holder.videoPanel.layoutParams.height
        val animator = ValueAnimator.ofInt(start, target)
        animator.duration = 220
        animator.addUpdateListener { a ->
            val v = a.animatedValue as Int
            val lp = holder.videoPanel.layoutParams
            lp.width = v
            lp.height = v
            holder.videoPanel.layoutParams = lp
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { onEnd?.invoke() }
        })
        animator.start()
    }

    /** Tap = play/pause; press-and-hold the left/right half = seek ∓5s, repeating while held. */
    private fun installVideoTouchListener(holder: ViewHolder) {
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var seeking = false
        var pendingLongPress: Runnable? = null

        holder.videoPanel.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    seeking = false
                    val width = v.width
                    val lp = Runnable {
                        seeking = true
                        startSeekRepeat(downX >= width / 2f)
                    }
                    pendingLongPress = lp
                    videoHandler.postDelayed(lp, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pendingLongPress?.let { videoHandler.removeCallbacks(it) }
                    pendingLongPress = null
                    cancelSeekRepeat()
                    if (!seeking && event.action == MotionEvent.ACTION_UP) togglePlayPause()
                    seeking = false
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    private fun startSeekRepeat(forward: Boolean) {
        cancelSeekRepeat()
        val step = 5000L
        val r = object : Runnable {
            override fun run() {
                val player = exoPlayer ?: return
                val dur = player.duration
                val pos = player.currentPosition
                val toPos = if (forward) {
                    if (dur > 0) (pos + step).coerceAtMost(dur) else pos + step
                } else {
                    (pos - step).coerceAtLeast(0)
                }
                player.seekTo(toPos)
                videoHandler.postDelayed(this, 500)
            }
        }
        seekRunnable = r
        videoHandler.post(r)
    }

    private fun cancelSeekRepeat() {
        seekRunnable?.let { videoHandler.removeCallbacks(it) }
        seekRunnable = null
    }

    /** True while a tile is expanded/playing — lets the activity intercept Back. */
    fun isVideoExpanded(): Boolean = playingHolder != null

    fun collapseActiveVideo(animate: Boolean = true) {
        if (playingHolder != null) collapseVideo(animate = animate)
    }

    /** Releases the shared player; call from the hosting activity's onDestroy. */
    fun releaseInlinePlayer() {
        videoHandler.removeCallbacks(progressRunnable)
        cancelSeekRepeat()
        playingHolder?.let {
            it.videoPlayer.player = null
            resetVideoExpansionState(it)
        }
        playingHolder = null
        playingGuid = 0L
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun getFileIconForMimeType(mimeType: String): Int {
        return when {
            mimeType.startsWith("application/pdf") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("application/zip") || mimeType.startsWith("application/x-") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("text/") -> R.drawable.ic_file_document_outline
            mimeType.startsWith("audio/") -> R.drawable.ic_music_note_outline
            mimeType.startsWith("video/") -> R.drawable.ic_file_document_outline
            else -> R.drawable.ic_file_document_outline
        }
    }

    /**
     * Sends a file download request to ConnectionService via the file server.
     * Works for both P2P and group chat messages.
     */
    private fun requestFileDownload(context: Context, msgGuid: Long, metaBytes: ByteArray?) {
        if (metaBytes == null) return
        try {
            val json = JSONObject(String(metaBytes))
            val name = json.getString("name")
            val hash = json.getString("hash")
            val size = json.optLong("size", 0)
            val intent = Intent(context, ConnectionService::class.java).apply {
                putExtra("command", "request_file")
                putExtra("guid", msgGuid)
                putExtra("name", name)
                putExtra("hash", hash)
                putExtra("size", size)
                putExtra("group_chat", groupChat)
                putExtra("chat_id", chatId)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("MessageAdapter", "requestFileDownload failed", e)
        }
    }

    private fun openFile(context: Context, file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.file_provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Always show chooser to let user pick the app
            val chooserIntent = Intent.createChooser(intent, context.getString(R.string.open_with))
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                context.startActivity(chooserIntent)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MessageAdapter", "Error opening file", e)
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}