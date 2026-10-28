package io.github.notisift

import io.github.notisift.data.Entry
import io.github.notisift.data.Policy
import io.github.notisift.engine.Action
import io.github.notisift.engine.AppMode
import io.github.notisift.engine.Category
import io.github.notisift.engine.Source
import kotlinx.coroutines.flow.first

/**
 * Made-up notifications for the `demo` build, so screenshots never show anyone's real drawer.
 * Package names are real apps (for their icons); every name and message is invented.
 */
object DemoData {
    private data class Sample(
        val pkg: String, val app: String, val title: String, val text: String,
        val category: Category, val action: Action, val source: Source, val reason: String, val minutesAgo: Int,
        /** Chat apps reuse one notification per chat; samples sharing a key show as one row. */
        val key: String? = null,
    )

    private val samples = listOf(
        Sample("com.whatsapp", "WhatsApp", "Alex Rivera", "Running 10 min late, save me a seat!", Category.PERSONAL, Action.KEEP, Source.PROTECTED, "A person wrote to you", 2),
        Sample("com.google.android.apps.messaging", "Messages", "Mom", "Call me when you land ❤️", Category.PERSONAL, Action.KEEP, Source.PROTECTED, "A person wrote to you", 9),
        Sample("com.google.android.apps.messaging", "Messages", "Sample Bank", "Your code is ******. Don't share it.", Category.SECURITY, Action.KEEP, Source.OTP, "Verification code", 4),
        Sample("com.chase.sig.android", "Chase", "Payment due Friday", "Your card payment of \$84.20 is due in 3 days", Category.FINANCE, Action.KEEP, Source.MODEL, "Money owed soon", 40),
        Sample("com.google.android.calendar", "Calendar", "Team standup", "In 10 minutes · Room 4B", Category.CALLS_ALERTS, Action.KEEP, Source.PROTECTED, "Calendar reminder", 12),
        Sample("com.whatsapp", "WhatsApp", "Weekend Hikers", "Sam: Trail is open, meet at 8?", Category.PERSONAL, Action.KEEP, Source.MODEL, "Group chat you reply to", 25),
        Sample("com.amazon.mShop.android.shopping", "Amazon", "Out for delivery", "Your package with Wireless earbuds arrives today", Category.ORDERS, Action.BUNDLE, Source.MODEL, "Useful, not urgent", 35),
        Sample("com.dd.doordash", "DoorDash", "Your order is on the way", "Taylor is 8 minutes away", Category.ORDERS, Action.KEEP, Source.MODEL, "Arriving in minutes", 6),
        Sample("com.dd.doordash", "DoorDash", "40% off tonight 🍕", "Treat yourself, deal ends at midnight", Category.PROMOTION, Action.BLOCK, Source.INBOX, "Marketing", 55),
        Sample("com.amazon.mShop.android.shopping", "Amazon", "Deals picked for you", "Save up to 60% on home essentials", Category.PROMOTION, Action.BLOCK, Source.MODEL, "Marketing", 90),
        Sample("com.ubercab", "Uber", "Ride receipt", "Thanks for riding with Jordan · \$18.40", Category.TRAVEL, Action.BUNDLE, Source.MODEL, "Receipt, read later", 120),
        Sample("com.google.android.apps.maps", "Maps", "How was Blue Bottle Café?", "Rate your visit to help others", Category.TRAVEL, Action.BLOCK, Source.CACHE, "Review request", 150),
        Sample("com.twitter.android", "X", "Trending in Tech", "New phone launch event announced for next week", Category.NEWS, Action.BUNDLE, Source.HEURISTIC, "Headline", 70),
        Sample("com.reddit.frontpage", "Reddit", "r/androiddev", "Popular: Tips for Jetpack Compose performance", Category.NEWS, Action.BUNDLE, Source.MODEL, "Feed update", 100),
        Sample("com.instagram.android", "Instagram", "jamie.design liked your photo", "and 12 others", Category.SOCIAL, Action.BUNDLE, Source.MODEL, "Social activity", 45),
        Sample("com.instagram.android", "Instagram", "Suggested for you", "See posts from accounts you may like", Category.SOCIAL, Action.BLOCK, Source.MODEL, "Suggestion", 180),
        Sample("com.linkedin.android", "LinkedIn", "You appeared in 9 searches", "See who's looking at your profile", Category.SOCIAL, Action.BLOCK, Source.MODEL, "Engagement bait", 200),
        Sample("com.linkedin.android", "LinkedIn", "Priya Shah sent you a message", "Hi! Are you free for a quick chat next week?", Category.WORK, Action.KEEP, Source.MODEL, "A person wrote to you", 30),
        Sample("com.google.android.youtube", "YouTube", "New from Tech Explained", "How notifications really work on Android", Category.MEDIA, Action.BUNDLE, Source.HEURISTIC, "New video", 130),
        Sample("com.spotify.music", "Spotify", "Your Daily Mix is ready", "Fresh songs picked for you", Category.MEDIA, Action.BLOCK, Source.MODEL, "Recommendation", 240),
        Sample("com.nest.android", "Nest", "Front door", "Package detected", Category.HOME, Action.BUNDLE, Source.MODEL, "Home event", 60),
        Sample("com.teslamotors.tesla", "Tesla", "Charging complete", "Your car is charged to 80%", Category.HOME, Action.BUNDLE, Source.APP_POLICY, "Your app choice", 95),
        Sample("com.robinhood.android", "Robinhood", "Market update", "Your watchlist is up 1.2% today", Category.MARKETS, Action.BUNDLE, Source.MODEL, "Price move", 80),
        Sample("com.fitbit.FitbitMobile", "Fitbit", "Goal reached 🎉", "You hit 10,000 steps", Category.HEALTH, Action.BUNDLE, Source.MODEL, "Wellness", 160),
        Sample("com.google.android.gm", "Gmail", "Newsletter: This week in design", "5 stories you might have missed", Category.PROMOTION, Action.BUNDLE, Source.INBOX, "Inbox: not important", 110),
        Sample("com.google.android.gm", "Gmail", "Sam Lee", "Re: Trip plans, I booked the cabin!", Category.PERSONAL, Action.KEEP, Source.MODEL, "A person wrote to you", 15),
        Sample("com.spotify.music", "Spotify", "Update available", "Get the latest features", Category.SYSTEM, Action.BLOCK, Source.HEURISTIC, "App update nag", 300),
        Sample("com.amazon.mShop.android.shopping", "Amazon", "Delivered", "Your package was left at the front door", Category.ORDERS, Action.BUNDLE, Source.MODEL, "Done, read later", 26 * 60),
        Sample("com.reddit.frontpage", "Reddit", "r/foldables", "Popular: Best apps for the inner screen", Category.NEWS, Action.BUNDLE, Source.MODEL, "Feed update", 28 * 60),
        Sample("com.nest.android", "Nest", "Backyard", "Motion detected", Category.HOME, Action.BUNDLE, Source.MODEL, "Home event", 3 * 24 * 60),
        Sample("com.robinhood.android", "Robinhood", "Weekly recap", "Your portfolio moved +2.4% this week", Category.MARKETS, Action.BUNDLE, Source.MODEL, "Price move", 9 * 24 * 60),
    )

    /** A busy neighbourhood group: enough messages on top today for the "Busy group chats" suggestion. */
    private val busyGroup = listOf("Dana: Has anyone seen a grey cat?", "Lee: Street sweeping is tomorrow", "Ravi: Lost keys found by the park",
        "Dana: Thanks all!", "Mia: Bake sale on Saturday", "Lee: Power is back on").mapIndexed { i, line ->
        Sample("com.whatsapp", "WhatsApp", "Maple Street Neighbors (${24 + i} messages): ~ ${line.substringBefore(':')}", line.substringAfter(": "),
            Category.PERSONAL, Action.KEEP, Source.PROTECTED, "Conversation", 3 + i * 7, key = "demo|maple")
    }

    private val modes = mapOf(
        "com.teslamotors.tesla" to AppMode.BUNDLE,
        "com.spotify.music" to AppMode.BLOCK,
    )

    suspend fun seed(app: SiftApp) {
        val dao = app.dao
        if (dao.entries().first().isNotEmpty()) return
        app.settingsStore.update { it.copy(observe = false, aiEnabled = false) }
        val now = System.currentTimeMillis()
        (samples + busyGroup).forEachIndexed { i, s ->
            dao.insert(
                Entry(
                    key = s.key ?: "demo|$i", packageName = s.pkg, appLabel = s.app, channelId = "demo", title = s.title, text = s.text,
                    postedAt = now - s.minutesAgo * 60_000L, category = s.category.id, action = s.action.id, source = s.source.name,
                    confidence = 0.9, reason = s.reason, templateKey = "demo|$i", applied = true,
                ),
            )
        }
        dao.upsertPolicies(
            samples.distinctBy { it.pkg }.map {
                Policy(it.pkg, it.app, (modes[it.pkg] ?: AppMode.AUTO).id, appCategory = it.category.id, suggestedMode = AppMode.AUTO.id)
            },
        )
    }
}
