package com.liam.moozik

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.support.v4.media.session.MediaSessionCompat
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), RecognitionListener, TextToSpeech.OnInitListener {

    // --- DATA ---
    private var allMediaItems: MutableList<MediaItem> = mutableListOf()
    private var allTitles: MutableList<String> = mutableListOf()
    private var allPaths: MutableList<String> = mutableListOf()

    // --- PLAYER & PLAYLIST ---
    private var player: ExoPlayer? = null
    private lateinit var prefs: SharedPreferences
    private var playlistNames: MutableList<String> = mutableListOf()
    private var currentPlaylistSongs: MutableList<MediaItem> = mutableListOf()
    private var currentPlaylistTitles: MutableList<String> = mutableListOf()
    private var isShuffle = false
    private var repeatMode = Player.REPEAT_MODE_OFF

    // --- NOTIFICATION ---
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSessionCompat
    private val CHANNEL_ID = "moto_music_channel"

    // --- UI ELEMENTS ---
    private lateinit var flipper: ViewFlipper
    private lateinit var tvMiniTitle: TextView
    private lateinit var btnMiniPlay: Button
    private lateinit var btnTabAll: Button
    private lateinit var btnTabPl: Button
    // UI AI Box
    private lateinit var tvAiStatus: TextView
    private lateinit var ivAetherIcon: ImageView

    // --- AI & VOICE ---
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private lateinit var tts: TextToSpeech
    private var isWaiting = false
    private val handler = Handler(Looper.getMainLooper())

    // Runnable untuk reset Aether jadi tidur lagi
    private val sleepRunnable = Runnable {
        isWaiting = false
        updateAIStatus(AIState.SLEEPING)
        restoreMusicVolume() // Balikin volume kenceng
    }

    // Enum Status AI
    enum class AIState { SLEEPING, LISTENING, THINKING, SPEAKING }

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MUSIC_ACTION") {
                when (intent.getStringExtra("action_name")) {
                    "ACTION_PREV" -> { prevSong(); speak("Back") }
                    "ACTION_PLAY" -> togglePlay()
                    "ACTION_NEXT" -> { nextSong(); speak("Next") }
                    "ACTION_MODE" -> toggleMode()
                    "ACTION_MIC" -> { startVoice(); speak("Ready") }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        registerReceiver(internalReceiver, IntentFilter("MUSIC_ACTION"))
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "MotoMusicTag")
        prefs = getSharedPreferences("MotoMusicData", Context.MODE_PRIVATE)
        loadPlaylistNames()

        // Init UI
        flipper = findViewById(R.id.viewFlipper)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        btnMiniPlay = findViewById(R.id.btnMiniPlay)
        btnTabAll = findViewById(R.id.tabAllSongs)
        btnTabPl = findViewById(R.id.tabPlaylists)
        tvAiStatus = findViewById(R.id.tvAiStatus)
        ivAetherIcon = findViewById(R.id.ivAetherIcon)

        // Setup Buttons
        btnTabAll.setOnClickListener { switchTab(0) }
        btnTabPl.setOnClickListener { switchTab(1) }
        findViewById<Button>(R.id.btnBackToPl).setOnClickListener { switchTab(1) }
        btnMiniPlay.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.btnAddCurrentToPl).setOnClickListener { showAddToPlaylistDialog() }
        findViewById<Button>(R.id.btnNewPlaylist).setOnClickListener { showCreatePlaylistDialog() }

        // TOMBOL RESTART VOICE (P3K)
        findViewById<ImageButton>(R.id.btnRestartVoice).setOnClickListener {
            Toast.makeText(this, "Restarting Ears...", Toast.LENGTH_SHORT).show()
            startVoice()
        }

        tts = TextToSpeech(this, this)
        checkPermissions()

        // Default Status
        updateAIStatus(AIState.SLEEPING)

        // SETUP TOMBOL BANTUAN
        findViewById<ImageButton>(R.id.btnHelp).setOnClickListener {
            showHelpDialog()
        }

        // AUTO SHOW HELP (Hanya saat pertama kali install)
        val isFirstRun = prefs.getBoolean("FIRST_RUN", true)
        if (isFirstRun) {
            showHelpDialog()
            prefs.edit().putBoolean("FIRST_RUN", false).apply()
        }
    }

    // --- LOGIKA STATUS AI & VOLUME DUCKING ---

    private fun updateAIStatus(state: AIState) {
        when (state) {
            AIState.SLEEPING -> {
                tvAiStatus.text = "Zzz... (Aether Sleep)"
                tvAiStatus.setTextColor(Color.parseColor("#90CAF9")) // Biru muda
                ivAetherIcon.setImageResource(android.R.drawable.ic_lock_idle_low_battery) // Icon Tidur
            }
            AIState.LISTENING -> {
                tvAiStatus.text = "Listening..."
                tvAiStatus.setTextColor(Color.GREEN)
                ivAetherIcon.setImageResource(android.R.drawable.ic_btn_speak_now) // Icon Mic
                duckMusicVolume() // Kecilin lagu!
            }
            AIState.THINKING -> {
                tvAiStatus.text = "Processing..."
                tvAiStatus.setTextColor(Color.YELLOW)
            }
            AIState.SPEAKING -> {
                tvAiStatus.text = "Answering..."
                tvAiStatus.setTextColor(Color.CYAN)
            }
        }
    }

    private fun duckMusicVolume() {
        // Kecilin volume jadi 10% biar inputan mic jelas
        player?.volume = 0.1f
    }

    private fun restoreMusicVolume() {
        // Balikin volume jadi 100%
        player?.volume = 1.0f
    }

    private fun resetSleepTimer() {
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, 6000) // 6 detik diem -> Tidur
    }

    // --- VOICE LOGIC ---
    override fun onResult(hypothesis: String?) {
        val text = JSONObject(hypothesis ?: return).optString("text", "").lowercase()
        if (text.isEmpty()) return

        if (!isWaiting) {
            // MODE TIDUR -> Cuma dengerin "Aether"
            if (text.contains("aether") || text.contains("ether") || text.contains("either")) {
                isWaiting = true
                updateAIStatus(AIState.LISTENING) // Icon jadi Mic, Volume turun
                speak("Yes?")
                resetSleepTimer()
            }
            return
        }

        // MODE LISTENING
        resetSleepTimer()

        if (text.contains("open playlist")) {
            updateAIStatus(AIState.THINKING)
            for (name in playlistNames) {
                if (text.contains(name.lowercase())) {
                    openPlaylist(name); speak("Opening $name");
                    // Jangan langsung tidur, kasih jeda dikit
                    return
                }
            }
            speak("Playlist not found")
        }
        else if (text.startsWith("play ")) {
            updateAIStatus(AIState.THINKING)
            val keyword = text.removePrefix("play ").trim()
            fuzzySearchAndPlay(keyword)
        }
        else if (text.contains("stop")) { togglePlay(); speak("Stopped"); forceSleep() }
        else if (text.contains("next")) { nextSong(); speak("Next") }
        else if (text.contains("back")) { prevSong(); speak("Back") }
        else if (text.contains("shuffle")) { toggleMode() }
        else if (text.contains("sleep") || text.contains("cancel")) {
            speak("Bye")
            forceSleep()
        }
    }

    private fun forceSleep() {
        handler.removeCallbacks(sleepRunnable)
        handler.post(sleepRunnable)
    }

    // --- MUSIC ENGINE ---
    private fun playMusic(items: List<MediaItem>, index: Int) {
        if (player == null) player = ExoPlayer.Builder(this).build()
        player?.setMediaItems(items)
        player?.seekTo(index, 0)
        player?.prepare()
        player?.play()
        // Pastikan volume full saat mulai play manual
        player?.volume = 1.0f

        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateUI(); showNotification()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnMiniPlay.text = if (isPlaying) "⏸" else "▶"
                showNotification()
            }
        })
        btnMiniPlay.text = "⏸"
        showNotification()
    }

    // --- STANDARD FUNCTIONS (DISINGKAT BIAR MUAT) ---
    private fun togglePlay() { if (player?.isPlaying == true) player?.pause() else player?.play() }
    private fun nextSong() { if (player?.hasNextMediaItem() == true) player?.seekToNext() }
    private fun prevSong() { if (player?.hasPreviousMediaItem() == true) player?.seekToPrevious() else player?.seekTo(0) }
    private fun toggleMode() {
        if (!isShuffle && repeatMode == Player.REPEAT_MODE_OFF) { isShuffle = true; player?.shuffleModeEnabled = true; speak("Shuffle On") }
        else if (isShuffle) { isShuffle = false; player?.shuffleModeEnabled = false; repeatMode = Player.REPEAT_MODE_ALL; player?.repeatMode = repeatMode; speak("Repeat All") }
        else if (repeatMode == Player.REPEAT_MODE_ALL) { repeatMode = Player.REPEAT_MODE_ONE; player?.repeatMode = repeatMode; speak("Repeat One") }
        else { repeatMode = Player.REPEAT_MODE_OFF; player?.repeatMode = repeatMode; speak("Normal") }
        showNotification()
    }

    private fun fuzzySearchAndPlay(keyword: String) {
        val cleanKeyword = normalizeText(keyword)
        var bestScore = 0.0
        var bestIndex = -1
        var bestTitle = ""

        // Cari di SELURUH lagu (allTitles)
        for (i in allTitles.indices) {
            val cleanTitle = normalizeText(allTitles[i])

            // Cek kemiripan Judul
            val score = calculateSimilarity(cleanKeyword, cleanTitle)

            if (score > bestScore) {
                bestScore = score
                bestIndex = i
                bestTitle = allTitles[i]
            }
        }

        // SYARAT SKOR:
        // Saya turunkan jadi 0.35 (35% mirip) biar lebih pemaaf.
        // Asalkan 'mirip dikit', dia bakal play.
        if (bestScore > 0.35 && bestIndex != -1) {
            playMusic(allMediaItems, bestIndex)
            speak("Playing $bestTitle")

            // Pesan Debug biar tau kenapa dia milih lagu itu
            Toast.makeText(this, "Match: ${(bestScore*100).toInt()}% -> $bestTitle", Toast.LENGTH_SHORT).show()

            forceSleep()
        } else {
            speak("Not found")
            // Kasih tau user apa yang didenger AI vs apa yang terbaik di list
            Toast.makeText(this, "Failed. Heard: '$cleanKeyword' | Similar to: '$bestTitle' (${(bestScore*100).toInt()}%)", Toast.LENGTH_LONG).show()
            forceSleep()
        }
    }

    // --- TTS & NOTIF ---
    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) tts.language = Locale.US }
    private fun speak(t: String) {
        if (Build.VERSION.SDK_INT >= 21) tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MotoMusic Control", NotificationManager.IMPORTANCE_LOW)
            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        } else notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun showNotification() {
        val currentTitle = tvMiniTitle.text.toString()
        val isPlaying = player?.isPlaying == true
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val prevP = PendingIntent.getBroadcast(this, 0, Intent(this, NotificationReceiver::class.java).setAction("ACTION_PREV"), PendingIntent.FLAG_IMMUTABLE)
        val playP = PendingIntent.getBroadcast(this, 0, Intent(this, NotificationReceiver::class.java).setAction("ACTION_PLAY"), PendingIntent.FLAG_IMMUTABLE)
        val nextP = PendingIntent.getBroadcast(this, 0, Intent(this, NotificationReceiver::class.java).setAction("ACTION_NEXT"), PendingIntent.FLAG_IMMUTABLE)
        val micP = PendingIntent.getBroadcast(this, 0, Intent(this, NotificationReceiver::class.java).setAction("ACTION_MIC"), PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(BitmapFactory.decodeResource(resources, android.R.drawable.ic_media_play)) // Ganti icon app
            .setContentTitle(currentTitle)
            .setContentText("Aether Active")
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevP)
            .addAction(playPauseIcon, "Play", playP)
            .addAction(android.R.drawable.ic_media_next, "Next", nextP)
            .addAction(android.R.drawable.ic_btn_speak_now, "Mic", micP)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(1, 2, 3).setMediaSession(mediaSession.sessionToken))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(1, notif)
    }

    // --- HELPERS ---
    private fun updateUI() {
        val idx = player?.currentMediaItemIndex ?: 0
        val uri = player?.currentMediaItem?.localConfiguration?.uri.toString()
        val realIdx = allPaths.indexOf(uri)
        if (realIdx != -1) tvMiniTitle.text = allTitles[realIdx]
    }

    // Playlist logic (sama)
    private fun loadPlaylistNames() { playlistNames.clear(); val r = prefs.getString("ALL_PLAYLISTS", "") ?: ""; if (r.isNotEmpty()) playlistNames.addAll(r.split("|")) }
    private fun savePlaylistNames() { prefs.edit().putString("ALL_PLAYLISTS", playlistNames.joinToString("|")).apply() }
    private fun showCreatePlaylistDialog() { val i = EditText(this); AlertDialog.Builder(this).setTitle("Nama Playlist").setView(i).setPositiveButton("Buat") { _, _ -> val n = i.text.toString(); if (n.isNotEmpty() && !playlistNames.contains(n)) { playlistNames.add(n); savePlaylistNames(); refreshPlaylistList() } }.show() }
    private fun showAddToPlaylistDialog() { val t = tvMiniTitle.text.toString(); val i = allTitles.indexOf(t); if (i == -1) return; val p = allPaths[i]; val l = playlistNames.toTypedArray(); AlertDialog.Builder(this).setTitle("Add to...").setItems(l) { _, w -> val pl = l[w]; val ex = prefs.getString("PL_$pl", "") ?: ""; if (!ex.contains(p)) { val n = if (ex.isEmpty()) p else "$ex|$p"; prefs.edit().putString("PL_$pl", n).apply(); Toast.makeText(this, "Added", Toast.LENGTH_SHORT).show() } }.show() }
    private fun refreshPlaylistList() { val rv = findViewById<RecyclerView>(R.id.rvPlaylists); rv.layoutManager = LinearLayoutManager(this); rv.adapter = PlaylistAdapter(playlistNames) { openPlaylist(it) } }
    private fun openPlaylist(name: String) { currentPlaylistTitles.clear(); currentPlaylistSongs.clear(); val r = prefs.getString("PL_$name", "") ?: ""; if (r.isNotEmpty()) { for (p in r.split("|")) { val i = allPaths.indexOf(p); if (i != -1) { currentPlaylistSongs.add(allMediaItems[i]); currentPlaylistTitles.add(allTitles[i]) } } }; val rv = findViewById<RecyclerView>(R.id.rvPlaylistSongs); rv.layoutManager = LinearLayoutManager(this); rv.adapter = SongAdapter(currentPlaylistTitles, List(currentPlaylistTitles.size){""}) { playMusic(currentPlaylistSongs, it) }; findViewById<TextView>(R.id.tvPlaylistName).text = name; flipper.displayedChild = 2 }

    // Tab logic (sama)
    private fun switchTab(index: Int) { flipper.displayedChild = index; if (index == 0) { btnTabAll.backgroundTintList = ContextCompat.getColorStateList(this, R.color.holo_blue_dark); btnTabAll.setTextColor(Color.WHITE); btnTabPl.setTextColor(Color.BLACK); btnTabPl.backgroundTintList = ContextCompat.getColorStateList(this, R.color.holo_blue_bright) } else { btnTabAll.backgroundTintList = ContextCompat.getColorStateList(this, R.color.holo_blue_bright); btnTabPl.backgroundTintList = ContextCompat.getColorStateList(this, R.color.holo_blue_dark); btnTabAll.setTextColor(Color.BLACK); btnTabPl.setTextColor(Color.WHITE) } }

    private fun loadMp3Files() { allMediaItems.clear(); allTitles.clear(); allPaths.clear(); val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; val proj = arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA); val cur = contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC"); cur?.use { while (it.moveToNext()) { allTitles.add(it.getString(0)); allPaths.add(it.getString(1)); allMediaItems.add(MediaItem.fromUri(it.getString(1))) } }; val rv = findViewById<RecyclerView>(R.id.rvAllSongs); rv.layoutManager = LinearLayoutManager(this); rv.adapter = SongAdapter(allTitles, List(allTitles.size){""}) { playMusic(allMediaItems, it) }; refreshPlaylistList(); initVoskModel() }
    private fun initVoskModel() { StorageService.unpack(this, "model", "model", { m -> model = m; startVoice() }, {}) }
    private fun startVoice() { try { speechService?.stop(); speechService = SpeechService(Recognizer(model, 16000.0f), 16000.0f); speechService?.startListening(this); updateAIStatus(AIState.SLEEPING) } catch(_:Exception){} }
    private fun checkPermissions() { val p = mutableListOf<String>(); if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= 33) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.READ_MEDIA_AUDIO) } else { if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.READ_EXTERNAL_STORAGE) }; if (p.isNotEmpty()) ActivityCompat.requestPermissions(this, p.toTypedArray(), 1) else loadMp3Files() }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) { if (gr.isNotEmpty() && gr[0] == PackageManager.PERMISSION_GRANTED) loadMp3Files() }
    override fun onPartialResult(h: String?) {}
    override fun onFinalResult(h: String?) { onResult(h) }
    override fun onError(e: Exception?) { startVoice() }
    override fun onTimeout() { startVoice() }
    override fun onDestroy() { unregisterReceiver(internalReceiver); super.onDestroy(); player?.release(); speechService?.shutdown(); tts.shutdown() }

    // --- UPDATE: OTAK PINTAR (FUZZY LOGIC) ---

    // 1. Membersihkan teks (buang simbol aneh)
    private fun normalizeText(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()
    }

    // 2. Hitung Kemiripan (0.0 - 1.0)
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val l = if (s1.length > s2.length) s1 else s2
        val s = if (s1.length > s2.length) s2 else s1
        if (l.isEmpty()) return 1.0
        return (l.length - levenshteinDistance(l, s)).toDouble() / l.length.toDouble()
    }

    // 3. Rumus Matematika Jarak Kata
    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val ll = lhs.length
        val rl = rhs.length
        var c = IntArray(ll + 1) { it }
        var nc = IntArray(ll + 1)
        for (j in 1..rl) {
            nc[0] = j
            for (i in 1..ll) {
                val cost = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                nc[i] = minOf(c[i] + 1, nc[i - 1] + 1, c[i - 1] + cost)
            }
            val t = c; c = nc; nc = t
        }
        return c[ll]
    }
    private fun showHelpDialog() {
        val message = """
            Welcome to Moozik! 🎧
            Control your offline music completely hands-free.
            
            🗣️ WAKE WORD:
            Just say: "Aether", "Ether", or "Either".
            (Wait for the green light/sound "Yes?")
            
            🎵 COMMANDS:
            • "Play [Song Title]" (e.g., "Play Numb")
            • "Open Playlist [Name]" (e.g., "Open Playlist Rock")
            • "Next" / "Skip"
            • "Back" / "Previous"
            • "Stop" / "Pause"
            • "Shuffle" / "Normal"
            • "Sleep" (To turn off listening mode)
            
            💡 TIPS:
            • If Aether stops responding, tap the 🔄 icon in the top box.
            • You can also tap the Mic icon in the notification bar to wake Aether up.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("How to use Aether AI")
            .setMessage(message)
            .setPositiveButton("Let's Ride! 🏍️", null)
            .setIcon(android.R.drawable.ic_dialog_info)
            .show()
    }





}