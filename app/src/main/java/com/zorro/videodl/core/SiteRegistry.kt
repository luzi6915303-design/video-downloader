package com.zorro.videodl.core

import com.zorro.videodl.core.handlers.BilibiliHandler
import com.zorro.videodl.core.handlers.DouyinHandler
import com.zorro.videodl.core.handlers.GenericHandler
import com.zorro.videodl.core.handlers.InstagramHandler
import com.zorro.videodl.core.handlers.TikTokHandler
import com.zorro.videodl.core.handlers.ToutiaoHandler
import com.zorro.videodl.core.handlers.TwitterHandler
import com.zorro.videodl.core.handlers.XiaohongshuHandler
import com.zorro.videodl.core.handlers.YouTubeHandler

/**
 * The one place that knows which sites exist. Register a new [SiteHandler]
 * here and it is picked up by the URL matcher, the login screen and the UI.
 */
object SiteRegistry {

    val handlers: List<SiteHandler> = listOf(
        YouTubeHandler,
        TwitterHandler,
        XiaohongshuHandler,
        TikTokHandler,
        DouyinHandler,
        BilibiliHandler,
        ToutiaoHandler,
        InstagramHandler,
    )

    /** Handlers that can supply cookies through the in-app WebView login. */
    val loginCapable: List<SiteHandler> get() = handlers.filter { it.loginUrl != null }

    fun resolve(url: String): SiteHandler = handlers.firstOrNull { it.matches(url) } ?: GenericHandler

    fun byId(id: String): SiteHandler =
        handlers.firstOrNull { it.id == id } ?: GenericHandler

    /** Pulls the first http(s) URL out of arbitrary shared/clipboard text. */
    fun extractUrl(text: String): String? =
        Regex("""https?://[^\s<>"'，。、]+""").find(text)?.value?.trimEnd('，', '。', '、', ',', '.', ')', '）')

    /** True when the text carries a link any handler — or yt-dlp generally — could take. */
    fun looksDownloadable(text: String): Boolean = extractUrl(text) != null
}
