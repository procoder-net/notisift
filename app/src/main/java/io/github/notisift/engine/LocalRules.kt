package io.github.notisift.engine

/**
 * Decisions that never need a model. Kept deliberately conservative: anything that might be
 * time-critical stays in the shade.
 */
object LocalRules {
    private val protectedAndroidCategories = setOf(
        "call", "alarm", "reminder", "event", "navigation", "transport", "stopwatch", "progress", "sys", "service", "missed_call", "location_sharing",
    )

    private val codeOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private const val CODE = "(?:[A-Z]-)?\\d{3,4}[- ]?\\d{0,4}"
    /** Stricter form for the looser patterns: at least 4 digits, so "100 off … use code" stays a promo. */
    private const val CODE4 = "(?:[A-Z]-)?(?:\\d{4,8}|\\d{3}[- ]\\d{3})"
    private val otp = listOf(
        Regex("\\b(otp|one[- ]time|verification|verify|security|login|sign[- ]?in|passcode|2fa|auth(entication)?)\\b.{0,60}?\\b$CODE4\\b", codeOptions),
        Regex("\\b(code|pin|otp|passcode|password)\\b\\W{0,20}(is|was|:|：)?\\W{0,10}$CODE\\b", codeOptions),
        Regex("\\b$CODE4\\b.{0,40}?\\b(is your|is the|code|otp|passcode|to (sign|log) ?in|to verify|to confirm)\\b", codeOptions),
    )
    private val chatCategories = setOf("msg", "call", "missed_call")
    /** SMS short codes ("64519") and all-caps brand senders ("TEMU", "AMZN-ALERTS") are businesses, not people. */
    private val businessSender = Regex("^(\\d{3,6}|[A-Z0-9&_\\-]{2,15})$")

    private val securityWords = Regex(
        "\\b(new sign[- ]?in|unusual (activity|login)|password (was )?changed|suspicious|fraud alert|security alert)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Returns a KEEP verdict if the notification must never be touched, else null. */
    fun protect(n: NotificationInput): Verdict? = when {
        n.isOngoing || !n.isClearable -> Verdict(Action.KEEP, Category.SYSTEM, 1.0, Source.PROTECTED, "Ongoing or not dismissible")
        n.hasMediaSession -> Verdict(Action.KEEP, Category.OTHER, 1.0, Source.PROTECTED, "Media playback")
        n.isSensitive -> Verdict(Action.KEEP, Category.SECURITY, 1.0, Source.OTP, "Hidden by Android (likely a code)")
        n.androidCategory in protectedAndroidCategories ->
            Verdict(Action.KEEP, Category.CALLS_ALERTS, 1.0, Source.PROTECTED, "Android category: ${n.androidCategory}")
        isCode(n) -> Verdict(Action.KEEP, Category.SECURITY, 1.0, Source.OTP, "Looks like a verification code")
        securityWords.containsMatchIn(n.title + " " + n.text) -> Verdict(Action.KEEP, Category.SECURITY, 1.0, Source.OTP, "Security alert")
        else -> null
    }

    private val digitRun = Regex("\\d{3,}")

    /** Masks anything that could be a code before it is written to disk. */
    fun maskDigits(s: String) = s.replace(digitRun) { "•".repeat(it.value.length) }

    fun isCode(n: NotificationInput): Boolean {
        val s = n.title + " " + n.text
        return otp.any { it.containsMatchIn(s) }
    }

    private fun fromBusiness(n: NotificationInput) = businessSender.matches(n.title.trim())

    /** Chats and calls from real people. Never sent to the model; removed only by an explicit per-app choice or correction. */
    fun isConversation(n: NotificationInput): Boolean =
        (n.isMessagingStyle || n.androidCategory in chatCategories) && !fromBusiness(n)

    /** Emails and business texts: the model may sort them, but only into keep or bundle. */
    fun isEmailLike(n: NotificationInput): Boolean =
        n.androidCategory == "email" || ((n.isMessagingStyle || n.androidCategory == "msg") && fromBusiness(n))

    fun keyword(n: NotificationInput, rules: List<KeywordRule>): Verdict? {
        if (rules.isEmpty()) return null
        val haystack = (n.appLabel + " " + n.title + " " + n.text).lowercase()
        val hit = rules.firstOrNull { it.keyword in haystack } ?: return null
        return Verdict(hit.action, Heuristics.category(n), 1.0, Source.KEYWORD, "Matched \"${hit.keyword}\"")
    }

    fun fromAppMode(mode: AppMode, n: NotificationInput, appCategory: String = ""): Verdict? {
        val action = when (mode) {
            AppMode.ALWAYS_KEEP -> Action.KEEP
            AppMode.BUNDLE -> Action.BUNDLE
            AppMode.SILENCE -> Action.SILENCE
            AppMode.BLOCK -> Action.BLOCK
            AppMode.AUTO, AppMode.UNMANAGED -> return null
        }
        return Verdict(action, Heuristics.category(n, appCategory), 1.0, Source.APP_POLICY, mode.label)
    }
}

/** Offline classifier used when no model is configured, and to label policy-driven decisions. */
object Heuristics {
    private val promo = Regex(
        "(\\d+\\s?% off|\\bsale\\b|\\bdeal(s)?\\b|coupon|promo|discount|limited[- ]time|offer|cashback|free shipping|flash|exclusive|don'?t miss|last chance|% ?off|save \\$|\\bbuy\\b|shop now|new arrivals|recommended for you|you might like|trending)",
        RegexOption.IGNORE_CASE,
    )
    private val orders = Regex("(order|shipped|delivered|out for delivery|package|tracking|arriving|dispatched|pickup)", RegexOption.IGNORE_CASE)
    private val finance = Regex("(debited|credited|payment|transaction|balance|statement|invoice|bill due|\\$\\d|₹|€|£|refund)", RegexOption.IGNORE_CASE)
    private val social = Regex("(liked|commented|followed|mentioned you|tagged you|reacted|new follower|shared a|posted|story|went live)", RegexOption.IGNORE_CASE)
    private val system = Regex("(update available|updated|backup|storage|battery|sync|downloaded|installed)", RegexOption.IGNORE_CASE)

    /** Money actually moving or owed (payments, debits, balances, bills), as opposed to price alerts or card offers. */
    fun isMoneyMovement(n: NotificationInput): Boolean = finance.containsMatchIn(n.title + " " + n.text)

    private val markets = Regex("(price alert|\\bstocks?\\b|\\bshares\\b|crypto|bitcoin|\\bbtc\\b|watchlist|52[- ]week|market (open|close)|limit order|order filled|options? expir)", RegexOption.IGNORE_CASE)
    private val travel = Regex("(flight|boarding|\\bgate\\b|check[- ]in|itinerary|your (ride|driver)|driver is|reservation|hotel|booking|departure|how was )", RegexOption.IGNORE_CASE)
    private val home = Regex("(motion detected|person detected|doorbell|someone is at|garage|thermostat|vacuum|\\bunlocked\\b|sentry|charging (complete|stopped)|vehicle)", RegexOption.IGNORE_CASE)
    private val health = Regex("(\\bsteps\\b|workout|walk detected|heart rate|sleep score|calories|exercise|activity goal|time to stand|medication)", RegexOption.IGNORE_CASE)
    private val media = Regex("(new episode|now streaming|watch now|playlist|podcast|new video|live now|premiere|new album|season \\d)", RegexOption.IGNORE_CASE)
    private val news = Regex("(breaking|headline|°|weather|forecast|\\brain\\b|storm|\\bnba\\b|\\bnfl\\b|\\bmlb\\b|\\bnhl\\b|premier league|final score| vs\\.? )", RegexOption.IGNORE_CASE)
    private val school = Regex("(assignment|homework|\\bgrades?\\b|teacher|\\bschool\\b|classroom|report card|field trip)", RegexOption.IGNORE_CASE)
    private val work = Regex("(meeting|invit(e|ation)|standup|deadline|pull request|review requested|\\bticket\\b|shared a (doc|file))", RegexOption.IGNORE_CASE)

    /** Best on-phone guess at the group: content first, then the app's store category, then what the app is. */
    fun category(n: NotificationInput, appCategory: String = ""): Category {
        val s = n.title + " " + n.text
        val app = n.packageName + " " + n.appLabel
        return when {
            LocalRules.isCode(n) -> Category.SECURITY
            LocalRules.isConversation(n) -> Category.PERSONAL
            else -> contentApp(appCategory, app) ?: byText(n, s, app, appCategory)
        }
    }

    /**
     * Apps whose notifications are content (news, social, video, games) keep their app's group: a headline about
     * a flight is still news, not travel. Null for other apps, whose text says more than their type.
     */
    fun contentApp(appCategory: String, app: String = ""): Category? =
        fromApp(appCategory, app).takeIf { it in CONTENT_APPS }

    /**
     * The group to show an already-logged notification in, using what is known now: groups are decided when a
     * notification arrives, and app types learned later (a scan, a fix) should still apply to what is waiting.
     */
    fun regroup(stored: Category, n: NotificationInput, appCategory: String): Category = when {
        stored == Category.PERSONAL || stored == Category.SECURITY -> stored
        else -> contentApp(appCategory, "${n.packageName} ${n.appLabel}")
            ?: if (stored == Category.OTHER) category(n, appCategory) else stored
    }

    private val CONTENT_APPS = setOf(Category.NEWS, Category.SOCIAL, Category.MEDIA, Category.GAMES)

    private fun byText(n: NotificationInput, s: String, app: String, appCategory: String): Category {
        return when {
            markets.containsMatchIn(s) || Category.MARKETS.apps?.containsMatchIn(app) == true -> Category.MARKETS
            finance.containsMatchIn(s) -> Category.FINANCE
            orders.containsMatchIn(s) -> Category.ORDERS
            home.containsMatchIn(s) || Category.HOME.apps?.containsMatchIn(app) == true -> Category.HOME
            school.containsMatchIn(s) || Category.SCHOOL.apps?.containsMatchIn(app) == true -> Category.SCHOOL
            travel.containsMatchIn(s) -> Category.TRAVEL
            health.containsMatchIn(s) || Category.HEALTH.apps?.containsMatchIn(app) == true -> Category.HEALTH
            promo.containsMatchIn(s) || n.androidCategory == "promo" -> Category.PROMOTION
            social.containsMatchIn(s) -> Category.SOCIAL
            media.containsMatchIn(s) -> Category.MEDIA
            news.containsMatchIn(s) || n.androidCategory == "recommendation" -> Category.NEWS
            work.containsMatchIn(s) -> Category.WORK
            system.containsMatchIn(s) || n.androidCategory == "status" -> Category.SYSTEM
            else -> fromApp(appCategory, app)
        }
    }

    /** When the text says nothing, what the app is usually decides it. */
    private fun fromApp(appCategory: String, app: String): Category = when (appCategory) {
        "game" -> Category.GAMES
        "news" -> Category.NEWS
        "social" -> Category.SOCIAL
        "productivity" -> Category.WORK
        "audio", "video" -> Category.MEDIA
        "maps" -> Category.TRAVEL
        // A group id: the scan's suggestion when the store has no Play category.
        in Category.entries.map { it.id } -> Category.fromId(appCategory)
        else -> listOf(Category.TRAVEL, Category.MEDIA).firstOrNull { it.apps?.containsMatchIn(app) == true } ?: Category.OTHER
    }

    fun classify(n: NotificationInput, appCategory: String = ""): Verdict {
        val c = category(n, appCategory)
        val action = when (c) {
            Category.PROMOTION, Category.GAMES -> Action.BUNDLE
            Category.NEWS, Category.SOCIAL, Category.SYSTEM, Category.MEDIA -> Action.BUNDLE
            else -> Action.KEEP
        }
        return Verdict(action, c, if (action == Action.KEEP) 1.0 else 0.8, Source.HEURISTIC, "Offline rules: ${c.label}")
    }
}
