// 由 tools/gen_icons.py 从 tools/icons 生成，勿手改
package app.palminspire.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun icon(name: String, vararg parts: Pair<String, Boolean>): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for ((d, filled) in parts) {
            val nodes = PathParser().parsePathString(d).toNodes()
            if (filled) addPath(nodes, fill = SolidColor(Color.Black))
            else addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

object AppIcons {
    val send: ImageVector by lazy { icon("send", "M21.5 2.5 10.8 13.2" to false, "M21.5 2.5 14 21.5l-3.2-8.3L2.5 10z" to false) }
    val paperclip: ImageVector by lazy { icon("paperclip", "M7.5 7.5v9a4.5 4.5 0 0 0 9 0V5.5a3 3 0 0 0-6 0v10a1.5 1.5 0 0 0 3 0v-8" to false) }
    val image: ImageVector by lazy { icon("image", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z" to false, "M6.75 9a1.75 1.75 0 1 0 3.5 0a1.75 1.75 0 1 0 -3.5 0Z" to false, "M21 15.5l-4.1-4.1a1.5 1.5 0 0 0-2.1 0L6 20" to false) }
    val camera: ImageVector by lazy { icon("camera", "M9 5.5h6l2 2.5h2.5a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2H7l2-2.5z" to false, "M8.5 13.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z" to false) }
    val file: ImageVector by lazy { icon("file", "M14.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7.5Z" to false, "M14.5 3V6a1.5 1.5 0 0 0 1.5 1.5h3" to false) }
    val fileText: ImageVector by lazy { icon("file-text", "M14.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7.5Z" to false, "M14.5 3V6a1.5 1.5 0 0 0 1.5 1.5h3" to false, "M9 11.5L15 11.5" to false, "M9 15L15 15" to false, "M9 18.5L13 18.5" to false) }
    val terminal: ImageVector by lazy { icon("terminal", "M5 4h14a2.5 2.5 0 0 1 2.5 2.5v11a2.5 2.5 0 0 1 -2.5 2.5h-14a2.5 2.5 0 0 1 -2.5 -2.5v-11a2.5 2.5 0 0 1 2.5 -2.5Z" to false, "M6.5 9.5L10 12L6.5 14.5" to false, "M12.5 15.5L17 15.5" to false) }
    val messageSquare: ImageVector by lazy { icon("message-square", "M21 15.5a2 2 0 0 1-2 2H7.5L3 21.5V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" to false) }
    val plus: ImageVector by lazy { icon("plus", "M12 5L12 19" to false, "M5 12L19 12" to false) }
    val history: ImageVector by lazy { icon("history", "M4.5 15a8 8 0 1 0 1.9-8.3L3.5 9.5" to false, "M3.5 4.5L3.5 9.5L8.5 9.5" to false, "M12 8.5L12 12.5L15 14" to false) }
    val settings: ImageVector by lazy { icon("settings", "M10.04 5.38 10.42 2.53 13.58 2.53 13.96 5.38A6.9 6.9 0 0 1 15.29 5.94L17.57 4.18 19.82 6.43 18.06 8.71A6.9 6.9 0 0 1 18.62 10.04L21.47 10.42 21.47 13.58 18.62 13.96A6.9 6.9 0 0 1 18.06 15.29L19.82 17.57 17.57 19.82 15.29 18.06A6.9 6.9 0 0 1 13.96 18.62L13.58 21.47 10.42 21.47 10.04 18.62A6.9 6.9 0 0 1 8.71 18.06L6.43 19.82 4.18 17.57 5.94 15.29A6.9 6.9 0 0 1 5.38 13.96L2.53 13.58 2.53 10.42 5.38 10.04A6.9 6.9 0 0 1 5.94 8.71L4.18 6.43 6.43 4.18 8.71 5.94A6.9 6.9 0 0 1 10.04 5.38Z" to false, "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z" to false) }
    val stop: ImageVector by lazy { icon("stop", "M8.5 6.5h7a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z" to false) }
    val check: ImageVector by lazy { icon("check", "M5 12.5L10 17.5L19 7" to false) }
    val close: ImageVector by lazy { icon("close", "M6 6L18 18" to false, "M18 6L6 18" to false) }
    val xCircle: ImageVector by lazy { icon("x-circle", "M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0Z" to false, "M9.25 9.25L14.75 14.75" to false, "M14.75 9.25L9.25 14.75" to false) }
    val checkCircle: ImageVector by lazy { icon("check-circle", "M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0Z" to false, "M8 12.4L10.8 15.2L16 9.2" to false) }
    val shieldCheck: ImageVector by lazy { icon("shield-check", "M12 2.5c2.5 1.5 5 2.5 7.5 3v6c0 5-3 8.5-7.5 10-4.5-1.5-7.5-5-7.5-10v-6c2.5-.5 5-1.5 7.5-3z" to false, "M9 11.5L11.25 13.75L15 9.5" to false) }
    val shieldAlert: ImageVector by lazy { icon("shield-alert", "M12 2.5c2.5 1.5 5 2.5 7.5 3v6c0 5-3 8.5-7.5 10-4.5-1.5-7.5-5-7.5-10v-6c2.5-.5 5-1.5 7.5-3z" to false, "M12 8L12 12.5" to false, "M12 15.5L12.01 15.5" to false) }
    val lock: ImageVector by lazy { icon("lock", "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-5a2 2 0 0 1 2 -2Z" to false, "M8 11V7.5a4 4 0 0 1 8 0V11" to false, "M12 15.5L12.01 15.5" to false) }
    val unlock: ImageVector by lazy { icon("unlock", "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-5a2 2 0 0 1 2 -2Z" to false, "M8 11V7.5a4 4 0 0 1 7.9-.9" to false, "M12 15.5L12.01 15.5" to false) }
    val chevronLeft: ImageVector by lazy { icon("chevron-left", "M15 6L9 12L15 18" to false) }
    val chevronRight: ImageVector by lazy { icon("chevron-right", "M9 6L15 12L9 18" to false) }
    val chevronDown: ImageVector by lazy { icon("chevron-down", "M6 9L12 15L18 9" to false) }
    val chevronUp: ImageVector by lazy { icon("chevron-up", "M6 15L12 9L18 15" to false) }
    val refresh: ImageVector by lazy { icon("refresh", "M3 12a9 9 0 0 1 9-9c2.5 0 5 1 6.5 2.5L21 8" to false, "M21 3L21 8L16 8" to false, "M21 12a9 9 0 0 1-9 9c-2.5 0-5-1-6.5-2.5L3 16" to false, "M3 21L3 16L8 16" to false) }
    val search: ImageVector by lazy { icon("search", "M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0Z" to false, "M15.5 15.5L20.5 20.5" to false) }
    val folder: ImageVector by lazy { icon("folder", "M3 17.5v-11A1.5 1.5 0 0 1 4.5 5h4.6a1.5 1.5 0 0 1 1.2.6l1.4 1.9h7.8A1.5 1.5 0 0 1 21 9v8.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5Z" to false) }
    val folderOpen: ImageVector by lazy { icon("folder-open", "M5.5 13.5l1.1-2.2a1.5 1.5 0 0 1 1.34-.8h11.96a1 1 0 0 1 .95 1.32l-2.05 6.15a1.5 1.5 0 0 1-1.43 1.03H4.5A1.5 1.5 0 0 1 3 17.5v-11A1.5 1.5 0 0 1 4.5 5h4.6a1.5 1.5 0 0 1 1.2.6l1.4 1.9h6.8A1.5 1.5 0 0 1 20 9v1.5" to false) }
    val user: ImageVector by lazy { icon("user", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z" to false, "M5 20a7 7 0 0 1 14 0" to false) }
    val users: ImageVector by lazy { icon("users", "M5.5 8.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z" to false, "M3 20a6 6 0 0 1 12 0" to false, "M15.5 5.5a3.5 3.5 0 0 1 0 7" to false, "M17 14.5a5.5 5.5 0 0 1 4 5.5" to false) }
    val wifi: ImageVector by lazy { icon("wifi", "M3 10.5a12.5 12.5 0 0 1 18 0" to false, "M6 13.5a8.5 8.5 0 0 1 12 0" to false, "M9 16.5a4.5 4.5 0 0 1 6 0" to false, "M11 19.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z" to true) }
    val alertTriangle: ImageVector by lazy { icon("alert-triangle", "M10.45 4.5 2.85 17.6a1.8 1.8 0 0 0 1.55 2.7h15.2a1.8 1.8 0 0 0 1.55-2.7L13.55 4.5a1.8 1.8 0 0 0-3.1 0z" to false, "M12 9L12 13.5" to false, "M11 16.75a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z" to true) }
    val alertCircle: ImageVector by lazy { icon("alert-circle", "M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0Z" to false, "M12 7.75L12 12.75" to false, "M11 16a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z" to true) }
    val play: ImageVector by lazy { icon("play", "M8.5 5 L19 12 L8.5 19 Z" to false) }
    val pause: ImageVector by lazy { icon("pause", "M8 5h1.5a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-1.5a1 1 0 0 1 -1 -1v-12a1 1 0 0 1 1 -1Z" to false, "M14.5 5h1.5a1 1 0 0 1 1 1v12a1 1 0 0 1 -1 1h-1.5a1 1 0 0 1 -1 -1v-12a1 1 0 0 1 1 -1Z" to false) }
    val keyboard: ImageVector by lazy { icon("keyboard", "M4.5 6h15a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-15a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2Z" to false, "M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M6.5 14h.01M17.5 14h.01M9.5 14h5" to false) }
    val moreVertical: ImageVector by lazy { icon("more-vertical", "M10.5 5.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0Z" to true, "M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0Z" to true, "M10.5 18.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0Z" to true) }
    val bot: ImageVector by lazy { icon("bot", "M7.5 8.5h9a2.5 2.5 0 0 1 2.5 2.5v6a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-6a2.5 2.5 0 0 1 2.5 -2.5Z" to false, "M12 8.5L12 6" to false, "M10.75 4.75a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z" to false, "M8.25 13.5a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z" to false, "M13.25 13.5a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z" to false) }
    val code: ImageVector by lazy { icon("code", "M15.5 6L21.5 12L15.5 18" to false, "M8.5 6L2.5 12L8.5 18" to false, "M13.5 4.5L10.5 19.5" to false) }
    val diff: ImageVector by lazy { icon("diff", "M3.8 6a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0Z" to false, "M15.8 18a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0Z" to false, "M6 8.2V14a4 4 0 0 0 4 4h2" to false, "M18 15.8V10a4 4 0 0 0-4-4h-2" to false, "m10.5 16 2 2-2 2M13.5 8l-2-2 2-2" to false) }
    val edit: ImageVector by lazy { icon("edit", "M17.5 3.5a2 2 0 0 1 3 3L7 20l-4.5 1.5L4 17z" to false, "M15 6l3 3" to false) }
    val listCheck: ImageVector by lazy { icon("list-check", "M4 7L5.5 8.5L8.5 5.5" to false, "M11.5 7L20 7" to false, "M4 16.5L5.5 18L8.5 15" to false, "M11.5 16.5L20 16.5" to false) }
    val sparkles: ImageVector by lazy { icon("sparkles", "M10.5 5.5l2 5.5 5.5 2-5.5 2-2 5.5-2-5.5-5.5-2 5.5-2z" to false, "M18.5 2.5L18.5 7.5" to false, "M16 5L21 5" to false) }
    val zap: ImageVector by lazy { icon("zap", "M13 2.5 4 13.5h7l-1 8 9-11h-7Z" to false) }
    val clock: ImageVector by lazy { icon("clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z" to false, "M12 7L12 12L15.5 14" to false) }
    val hourglass: ImageVector by lazy { icon("hourglass", "M6 3L18 3" to false, "M6 21L18 21" to false, "M7.5 3v3.5a2 2 0 0 0 .6 1.4L12 12l3.9-4.1a2 2 0 0 0 .6-1.4V3" to false, "M7.5 21v-3.5a2 2 0 0 1 .6-1.4L12 12l3.9 4.1a2 2 0 0 1 .6 1.4V21" to false) }
    val loader: ImageVector by lazy { icon("loader", "M12 2L12 5.5" to false, "M16.5 7.5L19 5" to false, "M18.5 12L22 12" to false, "M16.5 16.5L19 19" to false, "M12 18.5L12 22" to false, "M7.5 16.5L5 19" to false, "M2 12L5.5 12" to false, "M7.5 7.5L5 5" to false) }
    val eye: ImageVector by lazy { icon("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" to false, "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z" to false) }
    val globe: ImageVector by lazy { icon("globe", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z" to false, "M3 12L21 12" to false, "M8 12a4 9 0 1 0 8 0a4 9 0 1 0 -8 0Z" to false) }
    val enter: ImageVector by lazy { icon("enter", "M9 10L5 14L9 18" to false, "M5 14h10a4 4 0 0 0 4-4V5" to false) }
    val arrowUp: ImageVector by lazy { icon("arrow-up", "M12 19L12 5" to false, "M5 12L12 5L19 12" to false) }
    val arrowDown: ImageVector by lazy { icon("arrow-down", "M12 5L12 19" to false, "M5 12L12 19L19 12" to false) }
    val arrowLeft: ImageVector by lazy { icon("arrow-left", "M19 12L5 12" to false, "M12 5L5 12L12 19" to false) }
    val arrowRight: ImageVector by lazy { icon("arrow-right", "M5 12L19 12" to false, "M12 5L19 12L12 19" to false) }
    val link: ImageVector by lazy { icon("link", "M15.5 7.5h2a4.5 4.5 0 0 1 0 9h-2" to false, "M8.5 16.5h-2a4.5 4.5 0 0 1 0-9h2" to false, "M8.5 12L15.5 12" to false) }
    val copy: ImageVector by lazy { icon("copy", "M10 8h9.5a2 2 0 0 1 2 2v9.5a2 2 0 0 1 -2 2h-9.5a2 2 0 0 1 -2 -2v-9.5a2 2 0 0 1 2 -2Z" to false, "M4.5 16a2 2 0 0 1-2-2V4.5a2 2 0 0 1 2-2H14a2 2 0 0 1 2 2" to false) }
    val trash: ImageVector by lazy { icon("trash", "M4 6.5h16M9.5 6.5v-2h5v2M6 6.5l1 13a1.5 1.5 0 0 0 1.5 1.5h7a1.5 1.5 0 0 0 1.5-1.5l1-13" to false, "M10 11v6M14 11v6" to false) }
    val mic: ImageVector by lazy { icon("mic", "M12 2.5a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0v-6a3 3 0 0 0-3-3z" to false, "M18.5 10.5v1a6.5 6.5 0 0 1-13 0v-1" to false, "M12 18L12 21.5" to false) }
    val bell: ImageVector by lazy { icon("bell", "M18 8.5a6 6 0 0 0-12 0c0 4.6-1.6 6.1-2.6 7.1a1 1 0 0 0 .7 1.7h15.8a1 1 0 0 0 .7-1.7c-1-1-2.6-2.5-2.6-7.1z" to false, "M10.25 20.5a2 2 0 0 0 3.5 0" to false) }
    val bellOff: ImageVector by lazy { icon("bell-off", "M8.2 3.75A6 6 0 0 1 18 8.5c0 2.6.5 4.3 1.15 5.5" to false, "M6.3 6.5c-.2.6-.3 1.27-.3 2 0 4.6-1.6 6.1-2.6 7.1a1 1 0 0 0 .7 1.7h12.4" to false, "M10.25 20.5a2 2 0 0 0 3.5 0" to false, "M3.5 3.5L20.5 20.5" to false) }
    val layers: ImageVector by lazy { icon("layers", "M12 3.5L20.5 8L12 12.5L3.5 8Z" to false, "M3.5 12L12 16.5L20.5 12" to false, "M3.5 16L12 20.5L20.5 16" to false) }
    val gitBranch: ImageVector by lazy { icon("git-branch", "M4 5.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z" to false, "M4 18.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z" to false, "M16 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z" to false, "M6 7.5v9M18 10a6 6 0 0 1-6 6H8" to false) }
    val wrench: ImageVector by lazy { icon("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.75-3.75a6 6 0 0 1-7.9 7.9l-6.9 6.9a2.1 2.1 0 0 1-3-3l6.9-6.9a6 6 0 0 1 7.9-7.9Z" to false) }
    val monitor: ImageVector by lazy { icon("monitor", "M5 4h14a2 2 0 0 1 2 2v8.5a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-8.5a2 2 0 0 1 2 -2Z" to false, "M12 16.5L12 19.5" to false, "M8.5 19.5L15.5 19.5" to false) }
    val smartphone: ImageVector by lazy { icon("smartphone", "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1 -2.5 2.5h-5a2.5 2.5 0 0 1 -2.5 -2.5v-14a2.5 2.5 0 0 1 2.5 -2.5Z" to false, "M11 18.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z" to true) }
    val power: ImageVector by lazy { icon("power", "M17.5 7a8 8 0 1 1-11 0" to false, "M12 3L12 11" to false) }
    val info: ImageVector by lazy { icon("info", "M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0Z" to false, "M11 8a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z" to true, "M12 11.5L12 16.5" to false) }
    val helpCircle: ImageVector by lazy { icon("help-circle", "M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0Z" to false, "M9.3 9.2a2.7 2.7 0 0 1 5.25.9c0 1.8-2.7 2.7-2.7 2.7" to false, "M11 16.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z" to true) }
    val rotateCcw: ImageVector by lazy { icon("rotate-ccw", "M4.5 15a8 8 0 1 0 1.9-8.3L3.5 9.5" to false, "M3.5 4.5L3.5 9.5L8.5 9.5" to false) }
    val key: ImageVector by lazy { icon("key", "M4.5 16a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z" to false, "M10.5 13.5L19.5 4.5" to false, "M19.5 4.5L21.5 6.5" to false, "M15.5 8.5L17.5 10.5" to false) }
    val upload: ImageVector by lazy { icon("upload", "M4 15.5v3A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5v-3" to false, "M12 14.5L12 4" to false, "M7.5 8.5L12 4L16.5 8.5" to false) }
    val download: ImageVector by lazy { icon("download", "M4 15.5v3A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5v-3" to false, "M12 4L12 14.5" to false, "M7.5 10L12 14.5L16.5 10" to false) }
    val cpu: ImageVector by lazy { icon("cpu", "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z" to false, "M10.5 9.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z" to false, "M9.5 2L9.5 5" to false, "M14.5 2L14.5 5" to false, "M9.5 19L9.5 22" to false, "M14.5 19L14.5 22" to false, "M2 9.5L5 9.5" to false, "M2 14.5L5 14.5" to false, "M19 9.5L22 9.5" to false, "M19 14.5L22 14.5" to false) }
    val archive: ImageVector by lazy { icon("archive", "M3 3h18a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-18a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z" to false, "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8" to false, "M10 12h4" to false) }
    val sun: ImageVector by lazy { icon("sun", "M7.5 12a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0Z" to false, "M12 3.5L12 5.5" to false, "M12 18.5L12 20.5" to false, "M3.5 12L5.5 12" to false, "M18.5 12L20.5 12" to false, "M6 6L7.5 7.5" to false, "M16.5 7.5L18 6" to false, "M6 18L7.5 16.5" to false, "M16.5 16.5L18 18" to false) }
    val moon: ImageVector by lazy { icon("moon", "M20.5 13.5A8.5 8.5 0 1 1 10.5 3.5a7.5 7.5 0 0 0 10 10z" to false) }
    val hand: ImageVector by lazy { icon("hand", "M9 11V5.5a1.5 1.5 0 0 1 3 0V11" to false, "M12 10V4.5a1.5 1.5 0 0 1 3 0V11" to false, "M15 10.5V6.5a1.5 1.5 0 0 1 3 0v7a6.5 6.5 0 0 1-6.5 6.5h-.8a6 6 0 0 1-4.4-1.9L4 15.3a1.5 1.5 0 0 1 2.2-2.1L9 15.5V8.5a1.5 1.5 0 0 1 3 0" to false) }
    val chartBar: ImageVector by lazy { icon("chart-bar", "M3 3L3 21L21 21" to false, "M8 18L8 11.5" to false, "M12.5 18L12.5 7" to false, "M17 18L17 13.5" to false) }
    val minus: ImageVector by lazy { icon("minus", "M5 12L19 12" to false) }
    val logout: ImageVector by lazy { icon("logout", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" to false, "M16 17L21 12L16 7" to false, "M21 12L9 12" to false) }
}
