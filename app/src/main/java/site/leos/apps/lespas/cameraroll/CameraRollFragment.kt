package site.leos.apps.lespas.cameraroll

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.*
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import java.lang.Integer.min
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.math.roundToInt

class CameraRollFragment : Fragment(), ConfirmDialogFragment.OnResultListener {
    private lateinit var controlViewGroup: ConstraintLayout
    private lateinit var mediaPager: RecyclerView
    private lateinit var quickScroll: RecyclerView
    private lateinit var divider: View
    private lateinit var nameTextView: TextView
    private lateinit var sizeTextView: TextView
    private lateinit var shareButton: ImageButton
    private var savedStatusBarColor = 0
    private var savedNavigationBarColor = 0

    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val camerarollModel: CamerarollViewModel by viewModels { CamerarollViewModelFactory(requireActivity().application, arguments?.getString(KEY_URI)) }

    private lateinit var mediaPagerAdapter: MediaPagerAdapter
    private lateinit var quickScrollAdapter: QuickScrollAdapter

    private lateinit var startWithThisMedia: String
    private var videoStopPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create adapter here so that it won't leak
        mediaPagerAdapter = MediaPagerAdapter(
            requireContext(),
            { toggleControlView(controlViewGroup.visibility == View.GONE) },
            { videoControlVisible-> toggleControlView(videoControlVisible) },
            {photo, imageView, type-> imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() }},
        )

        quickScrollAdapter = QuickScrollAdapter(
            { photo -> mediaPager.scrollToPosition(mediaPagerAdapter.findMediaPosition(photo))},
            { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) }
        )

        savedInstanceState?.apply {
            mediaPagerAdapter.setSavedStopPosition(getLong(STOP_POSITION))
            videoStopPosition = getLong(STOP_POSITION)
        }

        startWithThisMedia = arguments?.getString(KEY_SCROLL_TO) ?: ""

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                sharedElements?.put(names?.get(0)!!, mediaPager.findViewHolderForAdapterPosition(camerarollModel.getCurrentMediaIndex())?.itemView?.findViewById(R.id.media)!!)}
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_camera_roll, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        view.setBackgroundColor(Color.BLACK)

        controlViewGroup = view.findViewById<ConstraintLayout>(R.id.control_container).apply {
            // Prevent touch event passing to media pager underneath this
            setOnTouchListener { _, _ ->
                this.performClick()
                true
            }
        }
        nameTextView = view.findViewById(R.id.name)
        sizeTextView = view.findViewById(R.id.size)
        shareButton = view.findViewById(R.id.share_button)
        divider = view.findViewById(R.id.divider)

        shareButton.setOnClickListener {
            toggleControlView(false)

            val mediaToShare = mediaPagerAdapter.getMediaAtPosition(camerarollModel.getCurrentMediaIndex())
            startActivity(Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND
                type = mediaToShare.mimeType
                putExtra(Intent.EXTRA_STREAM, Uri.parse(mediaToShare.id))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }, null))
        }
        view.findViewById<ImageButton>(R.id.lespas_button).setOnClickListener {
            toggleControlView(false)

            if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance().show(parentFragmentManager, TAG_DESTINATION_DIALOG)
        }
        view.findViewById<ImageButton>(R.id.remove_button).setOnClickListener {
            toggleControlView(false)

            if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).let{
                it.setTargetFragment(this, DELETE_MEDIA_REQUEST_CODE)
                it.show(parentFragmentManager, CONFIRM_DIALOG)
            }
        }

        quickScroll = view.findViewById<RecyclerView>(R.id.quick_scroll).apply {
            adapter = quickScrollAdapter

            addItemDecoration(HeaderItemDecoration(this) { itemPosition->
                (adapter as QuickScrollAdapter).getItemViewType(itemPosition) == QuickScrollAdapter.DATE_TYPE
            })

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                var toRight = true
                val separatorWidth = resources.getDimension(R.dimen.camera_roll_date_grid_size).roundToInt()
                val mediaGridWidth = resources.getDimension(R.dimen.camera_roll_grid_size).roundToInt()

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    toRight = dx < 0
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        if ((recyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() < recyclerView.adapter?.itemCount!! - 1) {
                            // if date separator is approaching the header, perform snapping
                            recyclerView.findChildViewUnder(separatorWidth.toFloat(), 0f)?.apply {
                                if (width == separatorWidth) snapTo(this, recyclerView)
                                else recyclerView.findChildViewUnder(separatorWidth.toFloat() + mediaGridWidth / 3, 0f)?.apply {
                                    if (width == separatorWidth) snapTo(this, recyclerView)
                                }
                            }
                        }
                    }
                }

                private fun snapTo(view: View, recyclerView: RecyclerView) {
                    // Snap to this View if scrolling to left, or it's previous one if scrolling to right
                    if (toRight) recyclerView.smoothScrollBy(view.left - separatorWidth - mediaGridWidth, 0, null, 1000)
                    else recyclerView.smoothScrollBy(view.left, 0, null, 500)
                }
            })
        }

        mediaPager = view.findViewById<RecyclerView>(R.id.media_pager).apply {
            adapter = mediaPagerAdapter

            // Snap like a ViewPager
            PagerSnapHelper().attachToRecyclerView(this)

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    // scrollToPosition called
                    if (dx == 0 && dy == 0) newPositionSet()
                }
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    when(newState) {
                        RecyclerView.SCROLL_STATE_IDLE-> { newPositionSet() }
                        RecyclerView.SCROLL_STATE_DRAGGING-> { toggleControlView(false) }
                    }
                }
            })
        }

        // TODO dirty hack to reduce mediaPager's scroll sensitivity to get smoother zoom experience
        (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
            isAccessible = true
            set(mediaPager, (get(mediaPager) as Int) * 4)
        }

        savedInstanceState?.let {
            observeCameraRoll()
        } ?: run {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_PERMISSION_REQUEST)
            }
            else observeCameraRoll()
        }

        // Acquiring new medias
        destinationModel.getDestination().observe(viewLifecycleOwner, Observer { album ->
            album?.apply {
                // Acquire files
                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(arrayListOf(Uri.parse(mediaPagerAdapter.getMediaAtPosition(camerarollModel.getCurrentMediaIndex()).id)!!), album).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
        })

    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()
    }

    override fun onStart() {
        super.onStart()
        mediaPagerAdapter.initializePlayer()
    }

    override fun onResume() {
        //Log.e(">>>>>", "onResume $videoStopPosition")
        super.onResume()
        (requireActivity() as AppCompatActivity).window.run {
            savedStatusBarColor = statusBarColor
            savedNavigationBarColor = navigationBarColor
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
        }

        with(mediaPager.findViewHolderForAdapterPosition((mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())) {
            if (this is MediaPagerAdapter.VideoViewHolder) {
                this.resume()
            }
        }
    }

    override fun onPause() {
        //Log.e(">>>>>", "onPause")
        super.onPause()
        with(mediaPager.findViewHolderForAdapterPosition((mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())) {
            if (this is MediaPagerAdapter.VideoViewHolder) {
                videoStopPosition = this.pause()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STOP_POSITION, videoStopPosition)
    }

    override fun onStop() {
        super.onStop()
        mediaPagerAdapter.cleanUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        (requireActivity() as AppCompatActivity).run {
            supportActionBar!!.show()
            window.statusBarColor = savedStatusBarColor
            window.navigationBarColor = savedNavigationBarColor
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == WRITE_STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) observeCameraRoll()
            else if (requireActivity() is MainActivity) parentFragmentManager.popBackStack() else requireActivity().finish()
        }
    }

    // From ConfirmDialogFragment
    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive) camerarollModel.removeCurrentMedia()
    }

    private fun observeCameraRoll() {
        // Observing media list update
        camerarollModel.getMediaList().observe(viewLifecycleOwner, Observer {
            if (it.size == 0) {
                Snackbar.make(mediaPager, getString(R.string.empty_camera_roll), Snackbar.LENGTH_SHORT).setAnimationMode(Snackbar.ANIMATION_MODE_FADE).setBackgroundTint(resources.getColor(R.color.color_primary, null)).setTextColor(resources.getColor(R.color.color_text_light, null)).show()
                if (requireActivity() is MainActivity) parentFragmentManager.popBackStack() else requireActivity().finish()
            }

            // Set initial position if passed in arguments
            if (startWithThisMedia.isNotEmpty()) {
                camerarollModel.setCurrentMediaIndex(it.indexOfFirst { it.id == startWithThisMedia })
                startWithThisMedia = ""
            }

            // Populate list and scroll to correct position
            (mediaPager.adapter as MediaPagerAdapter).submitList(it)
            mediaPager.scrollToPosition(camerarollModel.getCurrentMediaIndex())
            (quickScroll.adapter as QuickScrollAdapter).submitList(it)
        })
    }

    private fun toggleControlView(show: Boolean) {
        TransitionManager.beginDelayedTransition(controlViewGroup, Slide(Gravity.BOTTOM).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() })
        controlViewGroup.visibility = if (show) View.VISIBLE else View.GONE

        if (mediaPagerAdapter.itemCount == 1) {
            // Disable quick scroll if there is only one media
            quickScroll.visibility = View.GONE
            divider.visibility = View.GONE
            // Disable share function if scheme of the uri shared with us is "file", this only happened when viewing a single file
            if (mediaPagerAdapter.getMediaAtPosition(0).id.startsWith("file")) shareButton.isEnabled = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun newPositionSet() {
        (mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().apply {
            camerarollModel.setCurrentMediaIndex(this)

            with(mediaPagerAdapter.getMediaAtPosition(this)) {
                nameTextView.text = name
                sizeTextView.text = "${dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}   |   ${Tools.humanReadableByteCountSI(eTag.toLong())}"

                var pos = quickScrollAdapter.findMediaPosition(this)
                if (pos == 1) pos = 0   // Show date separator for first item
                quickScroll.scrollToPosition(pos)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class CamerarollViewModelFactory(private val application: Application, private val fileUri: String?): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = CamerarollViewModel(application, fileUri) as T
    }

    class CamerarollViewModel(application: Application, fileUri: String?): AndroidViewModel(application) {
        private val mediaList = MutableLiveData<MutableList<Photo>>()
        private var currentMediaIndex = 0
        private val cr = application.contentResolver

        init {
            var medias = mutableListOf<Photo>()

            fileUri?.apply {
                val uri = Uri.parse(this)
                val photo = Photo(this, ImageLoaderViewModel.FROM_CAMERA_ROLL, "", "0", LocalDateTime.now(), LocalDateTime.MIN, 0, 0, "", 0)

                photo.mimeType = cr.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(fileUri).toLowerCase(Locale.ROOT)) ?: "image/jpeg"
                }
                when(uri.scheme) {
                    "content"-> {
                        cr.query(uri, null, null, null, null)?.use { cursor->
                            cursor.moveToFirst()
                            cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))?.let { photo.name = it }
                            // Store file size in property eTag
                            cursor.getString(cursor.getColumnIndex(OpenableColumns.SIZE))?.let { photo.eTag = it }
                        }
                    }
                    "file"-> uri.path?.let { photo.name = it.substringAfterLast('/') }
                }

                if (photo.mimeType.startsWith("video/")) {
                    MediaMetadataRetriever().run {
                        setDataSource(application, uri)
                        photo.width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                        photo.height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                        photo.dateTaken = Tools.getVideoFileDate(this, photo.name)
                        release()
                    }
                }
                else {
                    if (photo.mimeType == "image/jpeg" || photo.mimeType == "image/tiff") {
                        val exif = ExifInterface(cr.openInputStream(uri)!!)

                        // Get date
                        photo.dateTaken = Tools.getImageFileDate(exif, photo.name)?.let {
                            try {
                                LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                            } catch (e:Exception) {
                                e.printStackTrace()
                                LocalDateTime.now()
                            }
                        } ?: LocalDateTime.now()

                        // Store orientation in property shareId
                        photo.shareId = exif.rotationDegrees
                    }

                    BitmapFactory.Options().run {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeStream(cr.openInputStream(uri), null, this)
                        photo.width = outWidth
                        photo.height = outHeight
                    }
                }

                medias.add(photo)

            } ?: run { medias = Tools.getCameraRoll(cr, false) }

            mediaList.postValue(medias)
        }

        fun setCurrentMediaIndex(position: Int) { currentMediaIndex = position }
        fun getCurrentMediaIndex(): Int = currentMediaIndex
        //fun setCurrentMedia(media: Photo) { currentMediaIndex = mediaList.value!!.indexOf(media) }
        //fun setCurrentMedia(id: String) { currentMediaIndex = mediaList.value!!.indexOfFirst { it.id == id }}
        fun getMediaList(): LiveData<MutableList<Photo>> = mediaList
        //fun getMediaListSize(): Int = mediaList.value!!.size

        fun removeCurrentMedia() {
            val newList = mediaList.value?.toMutableList()

            newList?.run {
                cr.delete(Uri.parse(this[currentMediaIndex].id), null, null)
                removeAt(currentMediaIndex)

                // Move index to the end of the new list if item to removed is at the end of the list
                currentMediaIndex = min(currentMediaIndex, size-1)

                mediaList.postValue(this)
            }
        }
    }

    class MediaPagerAdapter(private val ctx: Context, private val photoClickListener: (Photo) -> Unit, private val videoClickListener: (Boolean) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {
        private lateinit var exoPlayer: SimpleExoPlayer
        private var currentVolume = 0f
        private var oldVideoViewHolder: VideoViewHolder? = null
        private var savedStopPosition = FAKE_POSITION

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(photo: Photo) {
                itemView.findViewById<PhotoView>(R.id.media).apply {
                    imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)

                    setOnPhotoTapListener { _, _, _ ->  photoClickListener(photo) }
                    setOnOutsidePhotoTapListener { photoClickListener(photo) }

                    maximumScale = 5.0f
                    mediumScale = 2.5f

                    ViewCompat.setTransitionName(this, photo.id)
                }
            }
        }

        inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private lateinit var videoView: PlayerView
            private lateinit var muteButton: ImageButton
            private var videoId = ""
            private var stopPosition = 0L

            @SuppressLint("ClickableViewAccessibility")
            fun bind(video: Photo) {
                if (savedStopPosition != FAKE_POSITION) {
                    stopPosition = savedStopPosition
                    savedStopPosition = FAKE_POSITION
                }
                muteButton = itemView.findViewById(R.id.exo_mute)
                videoView = itemView.findViewById(R.id.player_view)
                videoId = video.id

                itemView.findViewById<ConstraintLayout>(R.id.videoview_container).let {
                    // Fix view aspect ratio
                    if (video.height != 0) with(ConstraintSet()) {
                        clone(it)
                        setDimensionRatio(R.id.media, "${video.width}:${video.height}")
                        applyTo(it)
                    }

                    // TODO If user touch outside VideoView, how to sync video player control view
                    //it.setOnClickListener { clickListener(video) }
                }

                with(videoView) {
                    setControllerVisibilityListener { videoClickListener(it == View.VISIBLE) }
                    //setOnClickListener { videoClickListener(muteButton.visibility == View.VISIBLE) }
                    //ViewCompat.setTransitionName(this, video.id)
                }

                muteButton.setOnClickListener { toggleMute() }
            }

            fun hideControllers() { videoView.hideController() }
            fun setStopPosition(position: Long) {
                //Log.e(">>>","set stop position $position")
                stopPosition = position }

            // This step is important to reset the SurfaceView that ExoPlayer attached to, avoiding video playing with a black screen
            fun resetVideoViewPlayer() { videoView.player = null }

            fun resume() {
                //Log.e(">>>>", "resume playback at $stopPosition")
                exoPlayer.apply {
                    // Stop playing old video if swipe from it. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                    if (isPlaying) {
                        playWhenReady = false
                        stop()
                        oldVideoViewHolder?.apply {
                            if (this != this@VideoViewHolder) {
                                setStopPosition(currentPosition)
                            }
                        }
                    }
                    playWhenReady = true
                    setMediaItem(MediaItem.fromUri(videoId), stopPosition)
                    prepare()
                    oldVideoViewHolder?.resetVideoViewPlayer()
                    videoView.player = exoPlayer
                    oldVideoViewHolder = this@VideoViewHolder

                    // Maintain mute status indicator
                    muteButton.setImageResource(if (exoPlayer.volume == 0f) R.drawable.ic_baseline_volume_off_24 else R.drawable.ic_baseline_volume_on_24)
                }
            }

            fun pause(): Long {
                //Log.e(">>>>", "pause playback")
                // If swipe out to a new VideoView, then no need to perform stop procedure. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                if (oldVideoViewHolder == this) {
                    exoPlayer.apply {
                        playWhenReady = false
                        stop()
                        setStopPosition(currentPosition)
                    }
                }

                return stopPosition
            }

            private fun mute() {
                currentVolume = exoPlayer.volume
                exoPlayer.volume = 0f
                muteButton.setImageResource(R.drawable.ic_baseline_volume_off_24)
            }

            private fun toggleMute() {
                exoPlayer.apply {
                    if (volume == 0f) {
                        volume = currentVolume
                        muteButton.setImageResource(R.drawable.ic_baseline_volume_on_24)
                    }
                    else mute()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when(viewType) {
                TYPE_PHOTO-> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
                else-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when(holder) {
                is PhotoViewHolder-> holder.bind(currentList[position])
                else-> (holder as VideoViewHolder).bind(currentList[position])
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            //Log.e(">>>>>", "onViewAttachedToWindow $holder")
            if (holder is VideoViewHolder) {
                holder.resume()
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            //Log.e(">>>>>", "onViewDetachedFromWindow $holder")
            if (holder is VideoViewHolder) {
                holder.pause()
            }
        }

        override fun submitList(list: MutableList<Photo>?) {
            super.submitList(list?.toMutableList())
        }

        override fun getItemViewType(position: Int): Int {
            with(currentList[position].mimeType) {
                return when {
                    this.startsWith("video/") -> TYPE_VIDEO
                    else -> TYPE_PHOTO
                }
            }
        }

        fun getMediaAtPosition(position: Int): Photo = currentList[position]
        fun findMediaPosition(photo: Photo): Int = currentList.indexOf(photo)
        fun setSavedStopPosition(position: Long) { savedStopPosition = position }

        fun initializePlayer() {
            //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
            exoPlayer = SimpleExoPlayer.Builder(ctx).build()
            exoPlayer.addListener(object: Player.EventListener {
                override fun onPlaybackStateChanged(state: Int) {
                    super.onPlaybackStateChanged(state)

                    if (state == Player.STATE_ENDED) {
                        exoPlayer.playWhenReady = false
                        exoPlayer.seekTo(0L)
                        oldVideoViewHolder?.setStopPosition(0L)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    if (isPlaying) oldVideoViewHolder?.hideControllers()
                }
            })

            // Default mute the video playback during late night
            with(LocalDateTime.now().hour) {
                if (this >= 22 || this < 7) {
                    currentVolume = exoPlayer.volume
                    exoPlayer.volume = 0f
                }
            }
        }

        fun cleanUp() { exoPlayer.release() }

        companion object {
            private const val TYPE_PHOTO = 0
            private const val TYPE_VIDEO = 2

            private const val FAKE_POSITION = -1L
        }
    }

    class QuickScrollAdapter(private val clickListener: (Photo) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {

        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: Photo) {
                with(itemView.findViewById<ImageView>(R.id.photo)) {
                    imageLoader(item, this, ImageLoaderViewModel.TYPE_GRID)
                    setOnClickListener { clickListener(item) }
                }
                itemView.findViewById<ImageView>(R.id.play_mark).visibility = if (Tools.isMediaPlayable(item.mimeType)) View.VISIBLE else View.GONE
            }
        }

        inner class DateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: Photo) {
                with(item.dateTaken) {
                    itemView.findViewById<TextView>(R.id.month).text = this.monthValue.toString()
                    itemView.findViewById<TextView>(R.id.day).text = this.dayOfMonth.toString()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == MEDIA_TYPE) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll, parent, false))
            else DateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MediaViewHolder) holder.bind(currentList[position])
            else if (holder is DateViewHolder) holder.bind(currentList[position])
        }

        override fun submitList(list: MutableList<Photo>?) {
            list?.apply {
                // Group by date
                val listGroupedByDate = mutableListOf<Photo>()
                var currentDate = LocalDate.now().plusDays(1)
                for (media in this) {
                    if (media.dateTaken.toLocalDate() != currentDate) {
                        currentDate = media.dateTaken.toLocalDate()
                        listGroupedByDate.add(Photo("", ImageLoaderViewModel.FROM_CAMERA_ROLL, "", "", media.dateTaken, media.dateTaken, 0, 0, "", 0))
                    }
                    listGroupedByDate.add(media)
                }

                super.submitList(listGroupedByDate)
            }
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].id.isEmpty()) DATE_TYPE else MEDIA_TYPE

        fun findMediaPosition(photo: Photo): Int = currentList.indexOf(photo)

        companion object {
            private const val MEDIA_TYPE = 0
            const val DATE_TYPE = 1
        }
    }

    class PhotoDiffCallback : DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = if (oldItem.id.isEmpty() || newItem.id.isEmpty()) false else oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem == newItem
    }

    companion object {
        private const val KEY_SCROLL_TO = "KEY_SCROLL_TO"
        private const val KEY_URI = "KEY_URI"

        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "CAMERAROLL_ACQUIRING_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val DELETE_MEDIA_REQUEST_CODE = 3399

        private const val WRITE_STORAGE_PERMISSION_REQUEST = 6464

        private const val STOP_POSITION = "STOP_POSITION"

        @JvmStatic
        fun newInstance(scrollTo: String) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_SCROLL_TO, scrollTo) }}

        @JvmStatic
        fun newInstance(uri: Uri) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_URI, uri.toString()) }}
    }
}