package com.ahdownload.feature.downloads

import com.ahdownload.domain.download.DownloadRecord

/**
 * Maps internal download error codes to concise, actionable Arabic text.
 * Do not include raw URLs, headers, cookies, or exception messages in this user-facing summary.
 */
internal fun failureSummary(record: DownloadRecord): String = when (record.failureCode) {
    "network_error" -> "تعذر الوصول إلى المصدر بسبب الشبكة. تحقق من الاتصال ثم أعد المحاولة."
    "http_error" -> when (record.failureDetail) {
        "401" -> "يتطلب المصدر جلسة صالحة أو تسجيل الدخول."
        "403" -> "رفض المصدر الطلب (403). حدّث المصدر أو أعد المحاولة لاحقًا."
        "404", "410" -> "المصدر لم يعد متاحًا."
        "416" -> "تعذر استكمال النطاق المطلوب من المصدر. أعد المحاولة بمصدر محدّث."
        "429" -> "طلب المصدر الانتظار بسبب كثرة الطلبات. انتظر قليلًا ثم أعد المحاولة."
        else -> "تعذر الوصول إلى المصدر حاليًا. أعد المحاولة لاحقًا."
    }
    "invalid_response" -> "أعاد المصدر بيانات غير صالحة أو غير متوقعة."
    "storage_error", "destination_storage_error" ->
        "تعذر حفظ الملف. تحقق من مساحة التخزين وصلاحية مجلد الحفظ."
    "storage_space_low" ->
        "مساحة التخزين غير كافية لإكمال التنزيل. وفر مساحة ثم أعد المحاولة."
    "youtube_refresh_failed" ->
        "تعذر تحديث رابط YouTube. أعد تحليل الرابط للحصول على مصدر جديد."
    "manifest_download_failed" ->
        "تعذر تنزيل مصدر البث. جرّب تحليل الرابط مجددًا أو اختيار جودة أخرى."
    "mux_failed" ->
        "تعذر دمج مساري الفيديو والصوت. أعد المحاولة أو اختر صيغة متوافقة."
    "audio_extraction_error" ->
        "تعذر استخراج الصوت أو تحويله. جرّب صيغة صوتية أخرى."
    "cancelled" -> "تم إلغاء التنزيل."
    "paused" -> "التنزيل متوقف مؤقتًا. استأنفه للمتابعة."
    "non_terminal_state" -> "لم يصل التنزيل إلى حالة نهائية. تحقق من حالته ثم أعد المحاولة."
    else -> "تعذر إكمال التنزيل. أعد المحاولة، وإذا تكرر الخطأ افتح تفاصيل التشخيص."
}
