package com.example.androidfunscripterlite

import android.content.Intent
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import android.provider.OpenableColumns
import android.util.Log
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.androidfunscripterlite.databinding.ActivityMainBinding
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import org.json.JSONObject
import java.io.BufferedReader
import kotlin.math.max
import kotlin.math.min
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import kotlin.math.abs

data class ActionPoint(var time: Long, var position: Int)

class MainActivity : AppCompatActivity() {

    private var funscriptURI : Uri? = null
    private val handler = Handler(Looper.getMainLooper())
    private val openFunscriptLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(it, takeFlags)
                contentResolver.openInputStream(it)?.use { inputStream ->
                    val jsonString = inputStream.bufferedReader().use(BufferedReader::readText)
                    parseAndApplyFunscript(jsonString)
                }
                funscriptURI = it
            } catch (e: Exception) {
                Log.e("Funscript", "Error loading funscript", e)
                e.printStackTrace()
            }
        }
    }.apply {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
    }
    private var player: ExoPlayer? = null
    private lateinit var binding: ActivityMainBinding
    private var videoUri: Uri? = null
    private val actionPoints = mutableListOf<ActionPoint>()
    private var selectedActionPoints = mutableListOf<ActionPoint>()
    private var copiedActionPoints: List<ActionPoint>? = null

    private var heatmapHolder: SurfaceHolder? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false
    private val openVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(it, takeFlags)

                videoUri = it
                loadVideo(it)

            } catch (e: Exception) {
                Log.e("Funscript", "Error taking permission", e)
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.playerView.useController = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainConstraintLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply the insets as a margin to the view. This solution sets
            // only the bottom, left, and right dimensions, but you can apply whichever
            // insets are appropriate to your layout. You can also update the view padding
            // if that's more appropriate.
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
                leftMargin = insets.left
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }

            // Return CONSUMED if you don't want the window insets to keep passing
            // down to descendant views.
            WindowInsetsCompat.CONSUMED
        }

        binding.playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.playerView.useController = true
                    binding.playerView.showController()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.postDelayed({
                        binding.playerView.useController = false
                    }, 3000)
                    true
                }
                else -> false
            }
        }
        setupMenuButton()
        setupButtonClickListeners()
        setupVideoPlaybackListener()
        setupHeatmapTouchListener()
        binding.heatmapView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                heatmapHolder = holder
                drawHeatmap()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                heatmapHolder = holder
                drawHeatmap()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                heatmapHolder = null
            }
        })
        binding.selectVideoButton.setOnClickListener {
            openVideoLauncher.launch(arrayOf("video/*"))
            }
        binding.playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (binding.playerView.isControllerVisible) {
                        binding.playerView.hideController()
                    } else {
                        binding.playerView.showController()
                    }
                    true
                }
                else -> false
            }
        }
    }
    private fun findPreviousAction(currentTime: Long): ActionPoint? {
        return actionPoints.lastOrNull { it.time < currentTime }
    }

    private fun findNextAction(currentTime: Long): ActionPoint? {
        return actionPoints.firstOrNull { it.time > currentTime }
    }
    private fun setupMenuButton() {
        binding.menuButton.setOnClickListener {
            if (binding.menuPopup.visibility == View.VISIBLE) {
                binding.menuPopup.visibility = View.GONE
            } else {
                binding.menuPopup.visibility = View.VISIBLE
            }
        }

        binding.loadScriptButton.setOnClickListener {
            openFunscriptLauncher.launch(arrayOf(
                "application/json",
                "text/plain",
                "*/*"
            ))
            binding.menuPopup.visibility = View.GONE
        }

        binding.selectVideoButton.setOnClickListener {
            openVideoLauncher.launch(arrayOf("video/*"))
            binding.menuPopup.visibility = View.GONE
        }
    }
    private fun copyActionPoints() {
        if (selectedActionPoints.isNotEmpty()) {
            copiedActionPoints = selectedActionPoints.map { point ->
                ActionPoint(point.time, point.position)
            }
        }
    }

    private fun loadVideo(uri: Uri) {
        try {
            player?.release()
            player = ExoPlayer.Builder(this)
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                .build()

            binding.playerView.apply {
                player = this@MainActivity.player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_UP -> {
                            if (!useController) {
                                useController = true
                                showController()
                            } else {
                                hideController()
                                useController = false
                            }
                            true
                        }
                        else -> true
                    }
                }
            }

            player?.apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }

            setupVideoPlaybackListener()

        } catch (e: Exception) {
            Log.e("VideoPlayer", "Error loading video: ${e.message}")
        }
    }
    private fun parseAndApplyFunscript(jsonString: String) {
        try {
            val jsonObject = JSONObject(jsonString)
            val actionsArray = jsonObject.getJSONArray("actions")

            actionPoints.clear()

            for (i in 0 until actionsArray.length()) {
                val action = actionsArray.getJSONObject(i)
                val time = action.getLong("at")
                val position = action.getInt("pos")
                actionPoints.add(ActionPoint(time, position))
            }

            actionPoints.sortBy { it.time }
            drawHeatmap()

            Log.d("Funscript", "Loaded ${actionPoints.size} action points")
        } catch (e: Exception) {
            Log.e("Funscript", "Error parsing funscript: ${e.message}")
            e.printStackTrace()
        }
    }
    private var frameStepCounter = 0


    private fun setupButtonClickListeners() {
        binding.buttonPrevFrame.setOnClickListener {
            player?.let { player ->
                binding.playerView.useController = false
                val stepMs = when (frameStepCounter % 3) {
                    0, 1 -> -33.33
                    else -> -33.34
                }
                frameStepCounter = (frameStepCounter + 1) % 3
                player.seekTo(player.currentPosition + stepMs.toLong())
                binding.playerView.hideController()
                drawHeatmap()
            }
        }

        binding.buttonNextFrame.setOnClickListener {
            player?.let { player ->
                binding.playerView.useController = false
                val stepMs = when (frameStepCounter % 3) {
                    0, 1 -> 33.33
                    else -> 33.34
                }
                frameStepCounter = (frameStepCounter + 1) % 3
                player.seekTo(player.currentPosition + stepMs.toLong())
                binding.playerView.hideController()
                drawHeatmap()
            }
        }


        binding.buttonPrevAction.setOnClickListener {
            player?.let { player ->
                binding.playerView.useController = false
                val currentTime = player.currentPosition
                findPreviousAction(currentTime)?.let { prevAction ->
                    player.seekTo(prevAction.time)
                    binding.playerView.hideController()
                    drawHeatmap()
                }
            }
        }

        binding.buttonNextAction.setOnClickListener {
            player?.let { player ->
                binding.playerView.useController = false
                val currentTime = player.currentPosition
                findNextAction(currentTime)?.let { nextAction ->
                    player.seekTo(nextAction.time)
                    binding.playerView.hideController()
                    drawHeatmap()
                }
            }
        }

        binding.buttonSave.setOnClickListener { saveFunscript() }
        binding.buttonQuicksave.setOnClickListener { quickSaveFunscript() }

        binding.buttonPlay.setOnClickListener {
            player?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
        }

        binding.buttonPlay2.setOnClickListener {
            player?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
        }

        val numberButtonClickListener = View.OnClickListener { view ->
            val button = view as Button
            when (button.id) {
                R.id.button_plus_0_5 -> {
                    player?.let { player ->
                        val currentTime = player.currentPosition
                        val currentPoint = actionPoints.find { it.time == currentTime }

                        currentPoint?.let { point ->
                            val newPosition = minOf(point.position + 5, 100)
                            point.position = newPosition
                            selectedActionPoints.clear()
                            selectedActionPoints.add(point)
                            drawHeatmap()
                        }
                    }
                    return@OnClickListener
                }
                R.id.button_minus_0_5 -> {
                    player?.let { player ->
                        val currentTime = player.currentPosition
                        val currentPoint = actionPoints.find { it.time == currentTime }

                        currentPoint?.let { point ->
                            val newPosition = maxOf(point.position - 5, 0)
                            point.position = newPosition
                            selectedActionPoints.clear()
                            selectedActionPoints.add(point)
                            drawHeatmap()
                        }
                    }
                    return@OnClickListener
                }
                R.id.button_plus_0_1 -> {
                    player?.let { player ->
                        val currentTime = player.currentPosition
                        val currentPoint = actionPoints.find { it.time == currentTime }

                        currentPoint?.let { point ->
                            val newPosition = minOf(point.position + 1, 100)
                            point.position = newPosition
                            selectedActionPoints.clear()
                            selectedActionPoints.add(point)
                            drawHeatmap()
                        }
                    }
                    return@OnClickListener
                }
                R.id.button_minus_0_1 -> {
                    player?.let { player ->
                        val currentTime = player.currentPosition
                        val currentPoint = actionPoints.find { it.time == currentTime }

                        currentPoint?.let { point ->
                            val newPosition = maxOf(point.position - 1, 0)
                            point.position = newPosition
                            selectedActionPoints.clear()
                            selectedActionPoints.add(point)
                            drawHeatmap()
                        }
                    }
                    return@OnClickListener
                }
                R.id.button_0 -> 0
                R.id.button_1 -> 10
                R.id.button_2 -> 20
                R.id.button_3 -> 30
                R.id.button_4 -> 40
                R.id.button_5 -> 50
                R.id.button_6 -> 60
                R.id.button_7 -> 70
                R.id.button_8 -> 80
                R.id.button_9 -> 90
                R.id.button_10 -> 100
                else -> return@OnClickListener
            }.let { position ->
                player?.currentPosition?.let { addActionPoint(it, position) }
            }
        }
        for (i in 0..10) {
            val buttonId = resources.getIdentifier("button_$i", "id", packageName)
            findViewById<Button>(buttonId).setOnClickListener(numberButtonClickListener)
        }

        binding.buttonPlus05.setOnClickListener(numberButtonClickListener)
        binding.buttonMinus05.setOnClickListener(numberButtonClickListener)
        binding.buttonPlus01.setOnClickListener(numberButtonClickListener)
        binding.buttonMinus01.setOnClickListener(numberButtonClickListener)
        binding.buttonDelete.setOnClickListener {
            deleteSelectedActionPoints()
        }

        binding.copyAction.setOnClickListener {
            copyActionPoints()
        }

        binding.pasteAction.setOnClickListener {
            pasteActionPoints()
        }
    }

    private fun addActionPoint(time: Long, position: Int) {
        val existingPoint = actionPoints.find { it.time == time }

        if (existingPoint != null) {
            existingPoint.position = position

            selectedActionPoints.clear()
            selectedActionPoints.add(existingPoint)
        } else {
            val newActionPoint = ActionPoint(time, position)
            actionPoints.add(newActionPoint)
            actionPoints.sortBy { it.time }

            selectedActionPoints.clear()
            selectedActionPoints.add(newActionPoint)
        }

        drawHeatmap()
    }

    private fun quickSaveFunscript() {
        if (funscriptURI == null) {
            Toast.makeText(this, "Funscript location not set for quicksave.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        funscriptURI?.let { uri ->
            val json = createFunscriptJson()

            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                    // The outputStream will be automatically closed here,
                    // even if write() throws an exception or if everything is successful.
                } // ?.use ensures this block only runs if openOutputStream doesn't return null

                Toast.makeText(this, "Quicksaved to $uri", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                // Log the exception for debugging
                Log.e("QuickSave", "Error writing to funscriptURI: $uri", e)
                Toast.makeText(this, "Failed to quicksave: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            // This block executes if funscriptURI is null
            Toast.makeText(this, "Funscript location not set for quicksave.", Toast.LENGTH_SHORT)
                .show()
        }
    }


    private fun saveFunscript() {
        if (videoUri == null) {
            val toast = Toast.makeText(this, "Failed to save, the video was null.", Toast.LENGTH_SHORT) // in Activity
            toast.show()
            return
        }

        val json = createFunscriptJson()

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "${getVideoFileName()}.funscript")
        }

        createFileLauncher.launch(intent)
    }

    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(createFunscriptJson().toByteArray())
                        funscriptURI = uri
                    }
                } catch (e: Exception) {
                    val toast = Toast.makeText(this, "Failed to save, exception thrown.", Toast.LENGTH_SHORT)
                    toast.show()
                    e.printStackTrace()
                }
            }
        }
    }
    private fun getVideoFileName(): String {
        videoUri?.let { uri ->
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    return displayName.substringBeforeLast(".")
                }
            }
        }
        return "output"
    }
    private fun createFunscriptJson(): String {
        val json = JSONObject()
        val actionsArray = org.json.JSONArray()

        for (actionPoint in actionPoints) {
            val actionObject = JSONObject()
            actionObject.put("at", actionPoint.time)
            actionObject.put("pos", actionPoint.position)
            actionsArray.put(actionObject)
        }

        json.put("actions", actionsArray)
        return json.toString()
    }

    private fun deleteSelectedActionPoints() {
        actionPoints.removeAll(selectedActionPoints)
        selectedActionPoints.clear()
        drawHeatmap()
    }

    private fun pasteActionPoints() {
        val player = player ?: return
        copiedActionPoints?.let { copiedPoints ->
            val currentTime = player.currentPosition
            val timeOffset = currentTime - (copiedPoints.firstOrNull()?.time ?: currentTime)

            val newActionPoints = copiedPoints.map { copiedPoint ->
                ActionPoint(copiedPoint.time + timeOffset, copiedPoint.position)
            }

            actionPoints.addAll(newActionPoints)
            actionPoints.sortBy { it.time }

            selectedActionPoints.clear()
            selectedActionPoints.addAll(newActionPoints)

            player.seekTo(newActionPoints.lastOrNull()?.time ?: currentTime)

            drawHeatmap()
        }
    }

    private fun drawHeatmap() {
        synchronized(this) {
            Log.d("Heatmap", "Drawing heatmap with ${actionPoints.size} action points")
            heatmapHolder?.let { holder ->
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    canvas?.let {
                        drawHeatmapContent(it)
                    }
                } catch (e: Exception) {
                    Log.e("Heatmap", "Failed to lock canvas", e)
                } finally {
                    try {
                        canvas?.let {
                            holder.unlockCanvasAndPost(it)
                        }
                    } catch (e: Exception) {
                        Log.e("Heatmap", "Failed to unlock canvas", e)
                    }
                }
            } ?: Log.e("Heatmap", "Heatmap holder is null")
        }
    }

    private fun drawHeatmapContent(canvas: Canvas) {
        canvas.drawColor(Color.rgb(25, 25, 50))

        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()

        val gridPaint = Paint().apply {
            color = Color.rgb(40, 40, 70)
            strokeWidth = 1f
        }

        for (i in 0..10) {
            val x = width * i / 10
            val y = height * i / 10
            canvas.drawLine(x, 0f, x, height, gridPaint)
            canvas.drawLine(0f, y, width, y, gridPaint)
        }

        val player = player ?: return
        val currentTime = player.currentPosition
        val timeWindow = 2000L
        val startTime = currentTime - timeWindow
        val endTime = currentTime + timeWindow

        val linePaint = Paint().apply {
            color = Color.rgb(0, 255, 0)
            strokeWidth = 3f
            isAntiAlias = true
            style = Paint.Style.STROKE
        }

        val normalPointPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val selectedPointPaint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val extendedPoints = actionPoints.let { points ->
            val beforePoint = points.lastOrNull { it.time < startTime }
            val afterPoint = points.firstOrNull { it.time > endTime }

            val visiblePoints = points.filter { it.time in startTime..endTime }

            (beforePoint?.let { listOf(it) } ?: emptyList()) +
                    visiblePoints +
                    (afterPoint?.let { listOf(it) } ?: emptyList())
        }.sortedBy { it.time }

        if (extendedPoints.isNotEmpty()) {
            for (i in 0 until extendedPoints.size - 1) {
                val currentPoint = extendedPoints[i]
                val nextPoint = extendedPoints[i + 1]

                val timeDiff = (nextPoint.time - currentPoint.time) / 1000.0

                val posDiff = nextPoint.position - currentPoint.position

                val speed = abs(posDiff / timeDiff)

                val color = when {
                    speed >= 600 -> Color.BLUE
                    speed >= 400 -> Color.RED
                    speed > 0 -> {
                        val ratio = (speed / 400.0).coerceIn(0.0, 1.0)
                        val red = (255.0 * ratio).toInt()
                        val green = (255.0 * (1.0 - ratio)).toInt()
                        val blue = (255.0 * (1.0 - ratio)).toInt()
                        Color.rgb(red, green, blue)
                    }
                    else -> Color.WHITE
                }

                val segmentPaint = Paint().apply {
                    this.color = color
                    strokeWidth = 3f
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                }

                val x1 = ((currentPoint.time - startTime).toFloat() / (timeWindow * 2)) * width
                val y1 = height - (currentPoint.position.toFloat() / 100 * height)
                val x2 = ((nextPoint.time - startTime).toFloat() / (timeWindow * 2)) * width
                val y2 = height - (nextPoint.position.toFloat() / 100 * height)

                canvas.drawLine(x1, y1, x2, y2, segmentPaint)
            }

            extendedPoints.filter { it.time in startTime..endTime }.forEach { point ->
                val x = ((point.time - startTime).toFloat() / (timeWindow * 2)) * width
                val y = height - (point.position.toFloat() / 100 * height)
                val paint = if (selectedActionPoints.contains(point)) selectedPointPaint else normalPointPaint
                canvas.drawCircle(x, y, 4f, paint)
            }
        }

        val centerLinePaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
        }
        canvas.drawLine(width / 2, 0f, width / 2, height, centerLinePaint)
    }

    private fun setupVideoPlaybackListener() {
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        startHeatmapUpdateLoop()
                    }
                    Player.STATE_ENDED -> {
                        handler.removeCallbacksAndMessages(null)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startHeatmapUpdateLoop()
                } else {
                    handler.post { drawHeatmap() }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    handler.post { drawHeatmap() }
                }
            }
        })
    }
    private fun startHeatmapUpdateLoop() {
        handler.removeCallbacksAndMessages(null)

        val updateRunnable = object : Runnable {
            override fun run() {
                if (player?.isPlaying == true) {
                    handler.post { drawHeatmap() }
                    handler.postDelayed(this, 16)
                }
            }
        }

        handler.post(updateRunnable)
    }
    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }


    private fun setupHeatmapTouchListener() {
        binding.heatmapView.setOnTouchListener { _, event ->
            val timeWindow = 2000L
            val width = binding.heatmapView.width.toFloat()
            val currentTime = player?.currentPosition ?: 0L
            val startTime = currentTime - timeWindow

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.x
                    isDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val dragStartTime = startTime + (dragStartX / width * (timeWindow * 2)).toLong()
                        val currentDragTime = startTime + (event.x / width * (timeWindow * 2)).toLong()

                        val selectionStartTime = min(dragStartTime, currentDragTime)
                        val selectionEndTime = max(dragStartTime, currentDragTime)

                        selectedActionPoints.clear()
                        actionPoints.forEach { point ->
                            if (point.time in selectionStartTime..selectionEndTime) {
                                selectedActionPoints.add(point)
                            }
                        }
                        drawHeatmap()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }
}