package code.name.monkey.lost.fragments.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ATHUtil
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.lost.R
import code.name.monkey.lost.activities.tageditor.TagWriter
import code.name.monkey.lost.databinding.FragmentLyricsBinding
import code.name.monkey.lost.extensions.accentColor
import code.name.monkey.lost.extensions.materialDialog
import code.name.monkey.lost.extensions.openUrl
import code.name.monkey.lost.extensions.uri
import code.name.monkey.lost.fragments.base.AbsMainActivityFragment
import code.name.monkey.lost.glide.LostGlideExtension
import code.name.monkey.lost.glide.LostGlideExtension.asBitmapPalette
import code.name.monkey.lost.glide.LostGlideExtension.songCoverOptions
import code.name.monkey.lost.glide.palette.BitmapPaletteWrapper
import code.name.monkey.lost.helper.LyricsGetter
import code.name.monkey.lost.helper.MusicPlayerRemote
import code.name.monkey.lost.helper.MusicProgressViewUpdateHelper
import code.name.monkey.lost.lyrics.LrcView
import code.name.monkey.lost.model.AudioTagInfo
import code.name.monkey.lost.model.Song
import code.name.monkey.lost.model.SongTMPContainer
import code.name.monkey.lost.util.FileUtils
import code.name.monkey.lost.util.LyricUtil
import code.name.monkey.lost.util.UriUtil
import code.name.monkey.lost.util.color.MediaNotificationProcessor
import com.afollestad.materialdialogs.input.input
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.collections.set

class LyricsFragment : AbsMainActivityFragment(R.layout.fragment_lyrics),
    MusicProgressViewUpdateHelper.Callback {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!
    private lateinit var song: Song

    private lateinit var normalLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var editSyncedLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var cacheFile: File
    private var syncedLyrics: String = ""
    private lateinit var syncedFileUri: Uri

    private var lyricsType: LyricsType = LyricsType.NORMAL_LYRICS

    private val googleSearchLrcUrl: String
        get() {
            var baseUrl = "http://www.google.com/search?"
            var query = song.title + "+" + song.artistName
            query = "q=" + query.replace(" ", "+") + " lyrics"
            baseUrl += query
            return baseUrl
        }

    private lateinit var updateHelper: MusicProgressViewUpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Normal lyrics launcher
        normalLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    FileUtils.copyFileToUri(requireContext(), cacheFile, song.uri)
                }
            }
        editSyncedLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    requireContext().contentResolver.openOutputStream(syncedFileUri)?.use { os ->
                        (os as FileOutputStream).channel.truncate(0)
                        os.write(syncedLyrics.toByteArray())
                        os.flush()
                    }
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = Fade()
        exitTransition = Fade()
        _binding = FragmentLyricsBinding.bind(view)
        updateHelper = MusicProgressViewUpdateHelper(this, 500, 1000)
        updateTitleSong()
        setupLyricsView()
        loadLyrics()
        setupWakelock()
        setupViews()
        setupToolbar()
    }

    private fun setupLyricsView() {
        if (!isAdded) return // Ensure fragment is added

        val typeface = ResourcesCompat.getFont(requireContext(), R.font.google_sans_bold)
        if (typeface != null) {
            binding.lyricsView.setTypeface(typeface)
        }

        Glide.with(requireContext())
            .asBitmapPalette()
            .songCoverOptions(song)
            .load(LostGlideExtension.getSongModel(song))
            .into(object : CustomTarget<BitmapPaletteWrapper>(500, 500) {
                override fun onResourceReady(
                    resource: BitmapPaletteWrapper,
                    transition: Transition<in BitmapPaletteWrapper>?
                ) {
                    val processor = MediaNotificationProcessor(requireContext(), resource.bitmap)
                    binding.colorGradientBackground.setBackgroundColor(processor.backgroundColor)
                    val alphaPrimary = ColorUtil.withAlpha(processor.primaryTextColor, 0.5f)
                    binding.lyricsView.apply {
                        setCurrentColor(processor.primaryTextColor)
                        setNormalTextColor(alphaPrimary)
                        setTimeTextColor(alphaPrimary)
                        setTimelineColor(alphaPrimary)
                        setTimelineTextColor(alphaPrimary)
                    }
                    binding.normalLyrics.setTextColor(processor.primaryTextColor)
                    binding.noLyricsFound.setTextColor(processor.primaryTextColor)
                    binding.toolbar.setTitleTextColor(processor.primaryTextColor)
                    ToolbarContentTintHelper.colorizeToolbar(
                        binding.toolbar,
                        processor.primaryTextColor,
                        requireActivity()
                    )
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    // Set default colors if image loading fails
                    val defaultBackgroundColor = ATHUtil.resolveColor(requireContext(), R.attr.defaultFooterColor)
                    binding.colorGradientBackground.setBackgroundColor(defaultBackgroundColor)
                    val defaultTextColor = accentColor()
                    val alphaDefault = ColorUtil.withAlpha(defaultTextColor, 0.5f)
                    binding.lyricsView.apply {
                        setCurrentColor(defaultTextColor)
                        setNormalTextColor(alphaDefault)
                        setTimeTextColor(alphaDefault)
                        setTimelineColor(alphaDefault)
                        setTimelineTextColor(alphaDefault)
                    }
                    binding.normalLyrics.setTextColor(defaultTextColor)
                    binding.noLyricsFound.setTextColor(defaultTextColor)
                    binding.toolbar.setTitleTextColor(defaultTextColor)
                    ToolbarContentTintHelper.colorizeToolbar(
                        binding.toolbar,
                        defaultTextColor,
                        requireActivity()
                    )
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Not implemented
                }
            })

        binding.lyricsView.setDraggable(true, LrcView.OnPlayClickListener {
            MusicPlayerRemote.seekTo(it.toInt())
            return@OnPlayClickListener true
        })
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.lyricsView.updateTime(progress.toLong())
    }

    private fun setupViews() {
        binding.editButton.accentColor()
        binding.editButton.setOnClickListener {
            when (lyricsType) {
                LyricsType.SYNCED_LYRICS -> {
                    editSyncedLyrics()
                }
                LyricsType.NORMAL_LYRICS -> {
                    editNormalLyrics()
                }
            }
        }
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateTitleSong()
        setupLyricsView() // Update colors when song changes
        loadLyrics()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateTitleSong()
        setupLyricsView() // Update colors when service connects
        loadLyrics()
    }

    private fun updateTitleSong() {
        song = MusicPlayerRemote.currentSong
    }

    private fun setupToolbar() {
        mainActivity.setSupportActionBar(binding.toolbar)
        ToolbarContentTintHelper.colorBackButton(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupWakelock() {
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_lyrics, menu)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            binding.toolbar,
            menu,
            ATHToolbarActivity.getToolbarBackgroundColor(binding.toolbar)
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_search) {
            openUrl(googleSearchLrcUrl)
        }
        return false
    }

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("CheckResult")
    private fun editNormalLyrics(lyrics: String? = null) {
        val file = File(song.data)
        val content = lyrics ?: try {
            AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }

        val song = song

        materialDialog().show {
            title(res = R.string.edit_normal_lyrics)
            input(
                hintRes = R.string.paste_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                GlobalScope.launch {
                    if (VersionUtils.hasR()) {
                        cacheFile = TagWriter.writeTagsToFilesR(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )[0]
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                        normalLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        TagWriter.writeTagsToFiles(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )
                    }
                }
            }
            positiveButton(res = R.string.save) {
                loadNormalLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }


    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("CheckResult")
    private fun editSyncedLyrics(lyrics: String? = null) {
        val content = lyrics ?: LyricUtil.getStringFromLrc(LyricUtil.getSyncedLyricsFile(song))

        val song = song
        materialDialog().show {
            title(res = R.string.edit_synced_lyrics)
            input(
                hintRes = R.string.paste_timeframe_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                if (VersionUtils.hasR()) {
                    syncedLyrics = input.toString()
                    val lrcFile = LyricUtil.getSyncedLyricsFile(song)
                    if (lrcFile?.exists() == true) {
                        syncedFileUri =
                            UriUtil.getUriFromPath(requireContext(), lrcFile.absolutePath)
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(syncedFileUri)
                            )
                        editSyncedLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                        fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                        GlobalScope.launch {
                            cacheFile = TagWriter.writeTagsToFilesR(
                                requireContext(),
                                AudioTagInfo(listOf(song.data), fieldKeyValueMap, null)
                            )[0]
                            val pendingIntent = MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                            normalLyricsLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent).build()
                            )
                        }
                    }
                } else {
                    LyricUtil.writeLrc(song, input.toString())
                }
            }
            positiveButton(res = R.string.save) {
                loadLRCLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }

    private fun loadNormalLyrics() {
        val file = File(song.data)
        val lyrics = try {
            if(song.data.startsWith("https")) {
                try {
                    val title = song.title
                    val author = song.artistName
                    if (title != "Unknown" && author != "Unknown") {
                        val tmpContainer = SongTMPContainer(
                            title = title,
                            artistName = listOf(author),
                            data = "",
                            year = 0,
                            liked = false,
                            favorite = false,
                            rating = 0
                        )
                        LyricsGetter.fetchLyricsForSong(tmpContainer)
                    } else {
                        ""
                    }
                } catch (_: Exception){
                    ""
                }
            } else {
                AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
        binding.normalLyrics.isVisible = !lyrics.isNullOrEmpty()
        binding.noLyricsFound.isVisible = lyrics.isNullOrEmpty()
        binding.normalLyrics.text = lyrics
    }

    /**
     * @return success
     */
    private fun loadLRCLyrics(): Boolean {
        if(song.data.startsWith("https")) {
            try {
                val title = song.title
                val author = song.artistName
                if (title != "Unknown" && author != "Unknown") {
                    val tmpContainer = SongTMPContainer(
                        title = title,
                        artistName = listOf(author),
                        data = "",
                        year = 0,
                        liked = false,
                        favorite = false,
                        rating = 0
                    )
                    val lyrics = LyricsGetter.fetchLyricsForSong(tmpContainer)
                    binding.lyricsView.loadLrc(lyrics)
                    return true
                } else {
                    return false
                }
            } catch (_: Exception){
                return false
            }
        }
        val lrcFile = LyricUtil.getSyncedLyricsFile(song)
        if (lrcFile != null) {
            binding.lyricsView.loadLrc(lrcFile)
        } else {
            val embeddedLyrics = LyricUtil.getEmbeddedSyncedLyrics(song.data)
            if (embeddedLyrics != null) {
                binding.lyricsView.loadLrc(embeddedLyrics)
            } else {
                binding.lyricsView.setLabel(getString(R.string.empty))
                return false
            }
        }
        return true
    }

    private fun loadLyrics() {
        lyricsType = if (!loadLRCLyrics()) {
            binding.lyricsView.isVisible = false
            loadNormalLyrics()
            LyricsType.NORMAL_LYRICS
        } else {
            binding.normalLyrics.isVisible = false
            binding.noLyricsFound.isVisible = false
            binding.lyricsView.isVisible = true
            LyricsType.SYNCED_LYRICS
        }
    }

    override fun onResume() {
        super.onResume()
        updateHelper.start()
    }

    override fun onPause() {
        super.onPause()
        updateHelper.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (MusicPlayerRemote.playingQueue.isNotEmpty())
            mainActivity.expandPanel()
        _binding = null
    }

    enum class LyricsType {
        NORMAL_LYRICS,
        SYNCED_LYRICS
    }
}
