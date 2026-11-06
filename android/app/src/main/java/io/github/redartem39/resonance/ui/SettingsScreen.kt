package io.github.redartem39.resonance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.redartem39.resonance.AppVm
import io.github.redartem39.resonance.data.Updater
import io.github.redartem39.resonance.data.Library

@Composable
private fun FileRow(title: String, sub: String, content: @Composable () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(title, pal.onSurface, 15f, 600, maxLines = 2)
            T(sub, pal.onSurfaceVar, 12.5f, 500, maxLines = 4, lineHeight = 17f)
        }
        content()
    }
}

@Composable
fun SettingsScreen(vm: AppVm, pickFolder: () -> Unit, pickCookies: () -> Unit) {
    val pal = LocalPal.current
    val s = vm.settings
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("Поиск") {
            SwitchRow("Искать на YouTube", "Выключено — только локальная библиотека. Включено — и YouTube, и локально.", s.yt) {
                vm.applySettings(s.copy(yt = it)); vm.onQuery(vm.query); vm.toast(if (it) "Поиск на YouTube включён" else "Только локальный поиск", 2000)
            }
            SwitchRow("Играть сразу после загрузки", "Скачанный трек запускается автоматически.", s.autoplay) { vm.applySettings(s.copy(autoplay = it)) }
        }
        Panel("Библиотека") {
            FileRow("Загрузки", vm.downloadDir.absolutePath) {}
            vm.folders.forEachIndexed { i, u ->
                FileRow(vm.folderName(u), android.net.Uri.decode(u).substringAfter("tree/", u)) { Mini(null, "trash") { vm.removeFolder(i) } }
            }
            Tonal(onClick = pickFolder) { fg -> Ico("add", fg, 20.dp); T("Добавить папку", fg, 15f, 650) }
        }
        Panel("Загрузчик") {
            FileRow("NewPipe Extractor", "Достаёт аудио с YouTube. Обновляется вместе с приложением.") {}
            Hint("Скачивание с YouTube может противоречить условиям сервиса и авторским правам. Используй для контента, на который у тебя есть права.")
        }
        Panel("YouTube: вход") {
            Hint("Иногда YouTube блокирует загрузки по IP («подтвердите, что вы не бот»). Cookies запасного аккаунта это снимают. Используй только запасной аккаунт: cookies дают доступ к аккаунту, а автоматические загрузки могут его ограничить.")
            SwitchRow("Использовать cookies при загрузке", "Работает, когда файл cookies загружен.", s.ytCookies) { vm.applySettings(s.copy(ytCookies = it)) }
            val (present, count) = vm.cookieStatus
            FileRow("Файл cookies", if (present) "Загружено: $count cookies" else "Не загружены") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Mini("Импорт…", onClick = pickCookies); Mini("Удалить", enabled = present) { vm.removeCookies() } }
            }
        }
        Panel("Эффекты") {
            SwitchRow("Молния в эквалайзере", "Разряд по полосам при смене пресета и после настройки ползунка.", s.fx) { vm.applySettings(s.copy(fx = it)) }
        }
        Panel("Приложение") {
            SwitchRow("Играть, когда приложение закрыто", "Музыка не прерывается, если смахнуть приложение из списка недавних. Остановить можно из уведомления.", s.keepPlaying) { vm.applySettings(s.copy(keepPlaying = it)) }
        }
        Panel("Обновления") {
            val u = vm.upd
            val status = when {
                u == null -> "Нажми «Проверить», чтобы посмотреть версии на GitHub."
                !u.ok -> "Не удалось проверить: ${u.error}"
                u.releases.isEmpty() -> "На GitHub пока нет релизов с APK."
                u.hasUpdate -> "Доступна версия ${u.latest} (у тебя ${u.current})."
                else -> "Установлена последняя версия (${u.current})."
            }
            FileRow("Resonance ${vm.versionName}", status) { Mini("Проверить", "refresh") { vm.checkUpdates(true) } }
            SwitchRow("Проверять при запуске", "Только запрос к GitHub — ничего не ставится без твоего нажатия.", s.updates) { vm.applySettings(s.copy(updates = it)) }
            u?.releases?.forEach { r ->
                val c = Updater.cmp(r.version, vm.versionName)
                val label = if (c > 0) "Обновить" else if (c < 0) "Откатить" else "Переустановить"
                val note = r.notes.lineSequence().firstOrNull { it.isNotBlank() }?.replace(Regex("[*_`#]"), "")?.trim()?.take(90)
                FileRow("v${r.version}${if (c == 0) " · сейчас" else ""}${if (r.prerelease) " · пре-релиз" else ""}", r.date.take(10) + (if (!note.isNullOrEmpty()) " · $note" else "")) {
                    val busy = vm.updBusy == r.version
                    Mini(if (busy) "${vm.updPct}%" else label, busy = busy, enabled = vm.updBusy == null) { vm.installVersion(r.version) }
                }
            }
            Hint("Версии берутся из GitHub Releases (файлы Resonance-*.apk). Перед установкой проверяется контрольная сумма, а ставит APK сам Android — он спросит подтверждение. Откат на более старую версию Android разрешает только после удаления текущей (данные приложения при этом сотрутся).")
        }
        Panel("Оформление") {
            Seg(listOf("Тёмная", "Светлая"), if (vm.darkTheme) 0 else 1) { vm.setTheme(it == 0) }
        }
    }
}
