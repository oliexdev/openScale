/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale

import android.Manifest
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.LocaleList
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseModule
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.facade.BluetoothFacadeBindsModule
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.SettingsProvidesModule
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.shared.SnackbarEvent
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.CustomTestApplication
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders the Play Store / README screenshots with synthetic users on every connected phone and
 * tablet (foldables are skipped). Run with:
 *
 * ./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
 *
 * Output: app/build/outputs/connected_android_test_additional_output/…/<device>/ mirrors the
 * repository layout (fastlane/…, docs/screens/…); copy its content into the repository root.
 * The test uses its own in-memory database and settings file, so data on the device stays untouched.
 */
@HiltAndroidTest
class StoreScreenshotTest {

    private val hiltRule = HiltAndroidRule(this)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hiltRule).around(compose)

    @Inject lateinit var userFacade: UserFacade
    @Inject lateinit var measurementFacade: MeasurementFacade
    @Inject lateinit var settingsFacade: SettingsFacade
    @Inject lateinit var databaseRepository: DatabaseRepository

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var kind: String
    private var originalNightMode = ""
    private var originalLocales = LocaleList.getEmptyLocaleList()

    @Before
    fun setUp() {
        val metrics = context.resources.displayMetrics
        val ratio = max(metrics.widthPixels, metrics.heightPixels).toFloat() /
            minOf(metrics.widthPixels, metrics.heightPixels)
        assumeTrue("Foldables are not part of the store listing", ratio > 1.4f)
        kind = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

        hiltRule.inject()
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        originalNightMode = shell("cmd uimode night").substringAfter(":").trim()
        originalLocales = localeManager().applicationLocales
        shell("cmd uimode night yes")
        localeManager().applicationLocales = LocaleList.forLanguageTags("en")

        runBlocking { seedData() }
        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @After
    fun tearDown() {
        if (!::scenario.isInitialized) return
        scenario.close()
        shell("cmd uimode night $originalNightMode")
        localeManager().applicationLocales = originalLocales
    }

    @Test
    fun captureStoreScreenshots() {
        open(R.string.route_title_overview)
        click(R.string.content_description_filter_chart_data, byDescription = true)
        click(R.string.time_range_last_30_days)
        if (exists(R.string.time_range_all_days)) pressBack()
        capture(1)
        if (kind == "tablet") captureReadme("tablet", "Adapts to every screen – phone, tablet, foldable")

        open(R.string.route_title_settings)
        click(R.string.settings_item_bluetooth)
        click(R.string.search_for_scales_button)
        capture(2)
        pressBack()

        open(R.string.route_title_graph)
        capture(3)
        // Insights keeps an infinite animation running, so Compose never reports idle there
        compose.mainClock.autoAdvance = false
        open(R.string.route_title_insights)
        Thread.sleep(3000)
        compose.mainClock.advanceTimeBy(3000)
        capture(4)
        open(R.string.route_title_statistics)
        compose.mainClock.autoAdvance = true
        capture(5)
        open(R.string.route_title_settings)
        click(R.string.settings_item_measurement_types)
        capture(6)
        if (kind == "tablet") {
            click(R.string.settings_item_general)
            captureReadme("tablet_settings", "Tune every detail to your needs")
        }
        open(R.string.route_title_table)
        capture(7)

        shell("cmd uimode night no")
        setLocale("ja")
        open(R.string.route_title_overview)
        capture(8)
    }

    // --- Navigation --------------------------------------------------------------------------

    private fun string(@StringRes id: Int): String {
        var text = ""
        scenario.onActivity { text = it.getString(id) }
        return text
    }

    private fun exists(@StringRes id: Int): Boolean =
        compose.onAllNodesWithText(string(id)).fetchSemanticsNodes().isNotEmpty()

    private fun click(@StringRes id: Int, byDescription: Boolean = false) {
        settle()
        val nodes = if (byDescription) compose.onAllNodesWithContentDescription(string(id))
        else compose.onAllNodesWithText(string(id))
        // Settings rows are clickable containers around the text; tapping the text hits them too
        val clickable = nodes.filter(hasClickAction())
        (if (clickable.fetchSemanticsNodes().isNotEmpty()) clickable else nodes).onFirst().performClick()
        settle()
    }

    /** Phones reach top-level screens through the drawer, tablets through the navigation rail. */
    private fun open(@StringRes route: Int) {
        settle()
        val menu = compose.onAllNodesWithContentDescription(string(R.string.content_desc_open_menu))
        if (menu.fetchSemanticsNodes().isNotEmpty()) menu.onFirst().performClick()
        click(route)
    }

    private fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        settle()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        if (compose.mainClock.autoAdvance) compose.waitForIdle() else compose.mainClock.advanceTimeBy(1000)
    }

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        }

    private fun setLocale(tag: String) {
        localeManager().applicationLocales = LocaleList.forLanguageTags(tag)
        Thread.sleep(4000) // let the system "keyboard language changed" toast disappear
    }

    private fun localeManager(): LocaleManager = context.getSystemService(LocaleManager::class.java)

    // --- Capture & store layout --------------------------------------------------------------

    /** Store screenshot n, written with the repository layout so the output can be copied as-is. */
    private fun capture(n: Int) {
        val (slug, caption) = SCREENS.getValue(n)
        val image = shoot(caption)
        val store = if (kind == "phone") "phoneScreenshots" else "tenInchScreenshots"
        save(image, "fastlane/metadata/android/en-GB/images/$store/${n}_en-GB.png")
        if (kind == "phone") save(image, "docs/screens/${n}_$slug.png")
    }

    private fun captureReadme(name: String, caption: String) = save(shoot(caption), "docs/screens/$name.png")

    private fun shoot(caption: String): Bitmap {
        settle()
        Thread.sleep(1500) // chart and list animations
        settle()
        val screen = instrumentation.uiAutomation.takeScreenshot().copy(Bitmap.Config.ARGB_8888, true)
        var statusBar = 0
        scenario.onActivity {
            statusBar = it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top
        }
        drawStatusBar(screen, statusBar)
        return storeLayout(screen, statusBar, caption)
    }

    private fun save(image: Bitmap, path: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: context.getExternalFilesDir("store_screenshots")!!
        val file = File(dir, path).apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Replaces the real status bar (notification icons, carrier state) with a clean one. */
    private fun drawStatusBar(screen: Bitmap, height: Int) {
        val dp = context.resources.displayMetrics.density
        val canvas = Canvas(screen)
        val bg = screen.getPixel(screen.width / 2, height + 2)
        canvas.drawRect(0f, 0f, screen.width.toFloat(), height.toFloat(), Paint().apply { color = bg })
        val dark = Color.luminance(bg) < 0.5f
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.rgb(31, 31, 31)
            textSize = 14 * dp
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val cy = height * 0.55f
        val margin = 24 * dp
        canvas.drawText("12:00", margin, cy - (fg.ascent() + fg.descent()) / 2, fg)

        val u = 1.1f * dp
        var x = screen.width - margin
        // battery
        val battery = RectF(x - 11 * u, cy - 3 * u, x - u, cy + 3 * u)
        canvas.drawRoundRect(battery, 1.5f * u, 1.5f * u, fg)
        canvas.drawRoundRect(RectF(x - u, cy - 1.3f * u, x, cy + 1.3f * u), 0.5f * u, 0.5f * u, fg)
        x = battery.left - 2.5f * u
        // cellular signal
        for (i in 0 until 4) {
            val barLeft = x - (4 - i) * 2.2f * u
            canvas.drawRoundRect(RectF(barLeft, cy + 3.5f * u - (i + 1) * 1.75f * u, barLeft + 1.5f * u, cy + 3.5f * u), 0.4f * u, 0.4f * u, fg)
        }
        x -= 4 * 2.2f * u + 2.5f * u
        // wifi
        val wifi = android.graphics.Path().apply {
            moveTo(x - 4.5f * u, cy + 3.5f * u)
            arcTo(RectF(x - 9 * u, cy - 5 * u, x, cy + 4 * u), 225f, 90f, false)
            close()
        }
        canvas.drawPath(wifi, fg)
    }

    /** Caption on the openScale blue, app screenshot inside a drawn device frame. */
    private fun storeLayout(screen: Bitmap, statusBar: Int, caption: String): Bitmap {
        val landscape = screen.width > screen.height
        val (w, h) = if (landscape) 2560 to 1440 else 1440 to 2560
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(52, 152, 219))

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = if (landscape) 88f else 96f
            textAlign = Paint.Align.CENTER
        }
        val lines = if (landscape) listOf(caption.replace("\n", " ")) else caption.lines()
        val lineHeight = text.textSize * 1.3f
        // fixed caption block (two lines on phones) so every device frame sits at the same height
        val blockLines = if (landscape) 1 else 2
        var y = (if (landscape) 70f else 110f) - text.ascent() + (blockLines - lines.size) * lineHeight / 2
        lines.forEach { canvas.drawText(it, w / 2f, y, text); y += lineHeight }
        y = (if (landscape) 70f else 110f) - text.ascent() + blockLines * lineHeight

        val bezel = if (landscape) 44f else 26f
        val screenRadius = if (landscape) 36f else 96f
        val screenWidth = if (landscape) 2060f else 1130f
        val scale = screenWidth / screen.width
        val left = (w - screenWidth) / 2f
        val top = y - lineHeight + text.descent() + (if (landscape) 60f else 90f) + bezel
        val display = RectF(left, top, left + screenWidth, top + screen.height * scale)
        val body = RectF(display).apply { inset(-bezel, -bezel) }
        val bodyRadius = screenRadius + bezel

        if (!landscape) { // side buttons
            val button = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 74, 80) }
            canvas.drawRoundRect(RectF(body.right - 4, body.top + 330, body.right + 7, body.top + 450), 6f, 6f, button)
            canvas.drawRoundRect(RectF(body.right - 4, body.top + 520, body.right + 7, body.top + 760), 6f, 6f, button)
        }
        canvas.drawRoundRect(body, bodyRadius, bodyRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 18, 20)
            setShadowLayer(48f, 0f, 16f, Color.argb(100, 0, 0, 0))
        })
        canvas.drawRoundRect(RectF(body).apply { inset(3f, 3f) }, bodyRadius - 3, bodyRadius - 3, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            shader = android.graphics.LinearGradient(
                0f, body.top, 0f, body.bottom,
                intArrayOf(Color.rgb(160, 166, 176), Color.rgb(78, 82, 90), Color.rgb(150, 156, 166)),
                null, Shader.TileMode.CLAMP,
            )
        })

        val shader = BitmapShader(screen, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(left, top) })
        }
        canvas.drawRoundRect(display, screenRadius, screenRadius, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        })

        val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 8, 10) }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(44, 46, 52) }
        if (landscape) {
            canvas.drawCircle(w / 2f, body.top + bezel / 2, 9f, ring)
            canvas.drawCircle(w / 2f, body.top + bezel / 2, 5f, lens)
        } else {
            val r = 11 * context.resources.displayMetrics.density * scale
            canvas.drawCircle(w / 2f, top + statusBar * scale * 0.55f, r + 3, ring)
            canvas.drawCircle(w / 2f, top + statusBar * scale * 0.55f, r, lens)
        }
        return out
    }

    // --- Synthetic data ----------------------------------------------------------------------

    /**
     * Curves follow real users' trends (keypoints ~2 months apart, oldest first); names, dates
     * and noise are synthetic.
     */
    private class Persona(
        val name: String,
        val gender: GenderType,
        val heightCm: Float,
        val birthDate: LocalDate,
        val spanDays: Int,
        val daysBetween: Double,
        val weight: FloatArray,
        val fat: FloatArray,
        val waterAt0Fat: Float,
        val muscleAt0Fat: Float,
        val bone: Float,
        val goals: List<Pair<MeasurementType.Key<*>, Float>> = emptyList(),
    )

    private val personas = listOf(
        Persona(
            "Alex", GenderType.MALE, 180f, LocalDate.of(1990, 4, 12), 488, 1.15,
            weight = floatArrayOf(78.8f, 80.8f, 86.8f, 87.8f, 83.7f, 82.4f, 86.0f, 86.9f, 81.6f),
            fat = floatArrayOf(15.5f, 16.5f, 18.8f, 19.4f, 17.6f, 16.9f, 18.5f, 19.0f, 16.6f),
            waterAt0Fat = 74.7f, muscleAt0Fat = 53.2f, bone = 3.8f,
            goals = listOf(MeasurementType.WEIGHT to 78f, MeasurementType.BODY_FAT to 15f),
        ),
        Persona(
            "Maria", GenderType.FEMALE, 166f, LocalDate.of(1993, 8, 21), 671, 3.0,
            weight = floatArrayOf(57.2f, 52.0f, 50.9f, 49.9f, 51.5f, 51.9f, 51.8f, 53.3f, 54.9f, 55.7f, 57.4f),
            fat = floatArrayOf(24.0f, 23.3f, 23.0f, 23.4f, 23.4f, 23.5f, 24.4f, 24.7f, 24.1f, 23.9f, 24.6f),
            waterAt0Fat = 71.0f, muscleAt0Fat = 47.0f, bone = 2.4f,
            goals = listOf(MeasurementType.WEIGHT to 55f, MeasurementType.BODY_FAT to 22f),
        ),
        Persona(
            "Jonas", GenderType.MALE, 176f, LocalDate.of(1986, 1, 30), 671, 4.0,
            weight = floatArrayOf(75.3f, 73.7f, 73.8f, 74.5f, 74.7f, 74.8f, 76.2f, 75.7f, 75.1f, 74.6f, 75.8f),
            fat = floatArrayOf(24.0f, 23.2f, 23.1f, 23.5f, 23.9f, 23.8f, 24.7f, 24.4f, 24.0f, 23.9f, 24.6f),
            waterAt0Fat = 74.7f, muscleAt0Fat = 53.2f, bone = 3.1f,
        ),
    )

    private suspend fun seedData() {
        LogManager.init(context, false)
        databaseRepository.insertAllMeasurementTypes(MeasurementType.seedRows())
        settingsFacade.setFirstAppStartCompleted(false)
        settingsFacade.setMyGoalsExpandedOverview(true)
        settingsFacade.setShowChartDataPoints(false)

        val types = measurementFacade.getAllMeasurementTypes().first()
        fun typeId(key: MeasurementType.Key<*>) = types.first { it.key == key }.id
        settingsFacade.saveSelectedTableTypeIds(
            listOf(MeasurementType.WEIGHT, MeasurementType.BMI, MeasurementType.BODY_FAT, MeasurementType.WATER, MeasurementType.MUSCLE)
                .map { typeId(it).toString() }.toSet()
        )
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()

        var firstUserId = 0
        personas.forEach { p ->
            val userId = userFacade.addUser(
                User(
                    name = p.name,
                    birthDate = p.birthDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                    gender = p.gender,
                    heightCm = p.heightCm,
                    activityLevel = ActivityLevel.MODERATE,
                    useAssistedWeighing = false,
                )
            ).getOrThrow().toInt()
            if (firstUserId == 0) firstUserId = userId

            val rnd = Random(p.name.hashCode().toLong())
            var daysAgo = p.spanDays.toDouble()
            while (true) {
                val day = daysAgo.roundToInt()
                val t = 1f - day.toFloat() / p.spanDays
                val fatNoise = (rnd.nextGaussian() * 0.3).toFloat()
                val fat = curve(p.fat, t) + fatNoise
                val timestamp = minOf(
                    LocalDate.now().minusDays(day.toLong()).atTime(6, 30)
                        .plusMinutes(rnd.nextInt(120).toLong()).atZone(zone).toInstant().toEpochMilli(),
                    now - TimeUnit.MINUTES.toMillis(5),
                )
                val values = listOf(
                    MeasurementType.WEIGHT to curve(p.weight, t) + (rnd.nextGaussian() * 0.35).toFloat(),
                    MeasurementType.BODY_FAT to fat,
                    MeasurementType.WATER to p.waterAt0Fat - 0.77f * fat + (rnd.nextGaussian() * 0.2).toFloat(),
                    MeasurementType.MUSCLE to p.muscleAt0Fat - 0.54f * fat + (rnd.nextGaussian() * 0.15).toFloat(),
                    MeasurementType.BONE to p.bone + (rnd.nextGaussian() * 0.03).toFloat(),
                ).map { (key, v) ->
                    MeasurementValue(measurementId = 0, typeId = typeId(key), floatValue = (v * 10).roundToInt() / 10f)
                }
                measurementFacade.saveMeasurement(Measurement(userId = userId, timestamp = timestamp), values)
                if (day == 0) break
                daysAgo = maxOf(0.0, daysAgo - p.daysBetween * (0.5 + rnd.nextDouble()))
            }

            p.goals.forEach { (key, value) ->
                userFacade.insertUserGoal(
                    UserGoals(
                        userId = userId,
                        measurementTypeId = typeId(key),
                        goalValue = value,
                        goalTargetDate = now + TimeUnit.DAYS.toMillis(180),
                        startDate = now - TimeUnit.DAYS.toMillis(60),
                    )
                )
            }
        }
        userFacade.setSelectedUserId(firstUserId).getOrThrow()
    }

    /** Smooth interpolation through evenly spaced keypoints, t in 0..1. */
    private fun curve(points: FloatArray, t: Float): Float {
        val pos = t.coerceIn(0f, 1f) * (points.size - 1)
        val i = pos.toInt().coerceAtMost(points.size - 2)
        val f = pos - i
        val s = f * f * (3 - 2 * f)
        return points[i] + (points[i + 1] - points[i]) * s
    }

    companion object {
        /** Screen number to docs/screens file slug and caption. */
        private val SCREENS = mapOf(
            1 to ("overview" to "Private by design –\nno account, no cloud"),
            2 to ("bluetooth" to "Works with 90+\nBluetooth scales"),
            3 to ("chart" to "See your trends\nat a glance"),
            4 to ("insights" to "Insights that explain\nyour progress"),
            5 to ("statistics" to "Evaluate your progress"),
            6 to ("body_metrics" to "27+ body metrics –\nor add your own"),
            7 to ("table" to "Your data stays yours:\nCSV & ZIP export"),
            8 to ("languages_themes" to "25+ languages,\nlight & dark theme"),
        )
    }
}

// --- Test wiring ---------------------------------------------------------------------------------

open class ScreenshotTestAppBase : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
}

@CustomTestApplication(ScreenshotTestAppBase::class)
interface ScreenshotTestApp

class ScreenshotTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, ScreenshotTestApp_Application::class.java.name, context)
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object ScreenshotDatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()

    @Provides fun provideUserDao(db: AppDatabase) = db.userDao()
    @Provides fun provideUserGoalsDao(db: AppDatabase) = db.userGoalsDao()
    @Provides fun provideMeasurementDao(db: AppDatabase) = db.measurementDao()
    @Provides fun provideMeasurementValueDao(db: AppDatabase) = db.measurementValueDao()
    @Provides fun provideMeasurementTypeDao(db: AppDatabase) = db.measurementTypeDao()
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsProvidesModule::class])
object ScreenshotSettingsModule {
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> {
        val file = ctx.preferencesDataStoreFile("store_screenshots")
        file.delete()
        return PreferenceDataStoreFactory.create { file }
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [BluetoothFacadeBindsModule::class])
interface ScreenshotBluetoothModule {
    @Binds
    @Singleton
    fun bind(impl: FakeBluetoothFacade): BluetoothFacade
}

/** Offers the devices of the original store screenshot instead of a real BLE scan. */
@Singleton
class FakeBluetoothFacade @Inject constructor(private val scaleFactory: ScaleFactory) : BluetoothFacade {
    private fun device(name: String, address: String, rssi: Int) =
        ScannedDeviceInfo(name, address, rssi, emptyList(), null).apply {
            val (supported, handler) = scaleFactory.getSupportingHandlerInfo(this)
            isSupported = supported
            determinedHandlerDisplayName = handler
        }

    private val scale = device("BF700", "C8:B2:1E:CA:31:29", -60)

    override val snackbarEventsFromConnector = MutableSharedFlow<SnackbarEvent>()
    override val scannedDevices = MutableStateFlow<List<ScannedDeviceInfo>>(emptyList())
    override val isScanning = MutableStateFlow(false)
    override val scanError = MutableStateFlow<String?>(null)
    override val connectedDeviceAddress = MutableStateFlow<String?>(null)
    override val connectionStatus = MutableStateFlow(ConnectionStatus.NONE)
    override val connectionError = MutableStateFlow<String?>(null)
    override val pendingUserInteractionEvent = MutableStateFlow<BluetoothEvent.UserInteractionRequired?>(null)
    override val savedDevice = MutableStateFlow<ScannedDeviceInfo?>(scale)
    override val savedDeviceSupport = MutableStateFlow<DeviceSupport?>(scaleFactory.getDeviceSupportFor(scale))

    override fun startScan(durationMs: Long) {
        scannedDevices.value = listOf(
            scale,
            device("LE-Bose Micro SoundLink", "60:AB:D2:CF:16:3A", -69),
            device("Samsung QN90BA 75", "D0:C2:4E:25:BF:A7", -86),
        )
    }

    override fun stopScan() {}
    override fun connectToSavedDevice() {}
    override fun attemptAutoConnectToSavedDevice() {}
    override fun disconnect() {}
    override fun saveAsPreferred(device: ScannedDeviceInfo) {}
    override fun removeSavedDevice() {}
    override fun setSavedTuning(profile: TuningProfile) {}
    override fun clearErrors() {}
    override fun clearPendingUserInteraction() {}
    override fun provideUserInteractionFeedback(type: BluetoothEvent.UserInteractionType, feedbackData: Any) {}
    override fun isBluetoothEnabled() = true
    override fun close() {}

    @Composable
    override fun DeviceConfigurationUi() {}
}
