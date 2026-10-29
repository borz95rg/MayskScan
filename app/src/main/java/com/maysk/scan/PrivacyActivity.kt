package com.maysk.scan

import android.os.Bundle

/** Политика конфиденциальности (на языке интерфейса). */
class PrivacyActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = screen("Политика конфиденциальности")
        val sections = when (I18n.lang) {
            "en" -> Privacy.EN
            "kk" -> Privacy.KK
            else -> Privacy.RU
        }
        sections.forEachIndexed { i, (head, para) ->
            if (head.isNotEmpty()) body.addView(text(head, 16f, bold = true).apply { setPadding(0, if (i == 0) 0 else dp(18), 0, dp(6)) })
            body.addView(text(para, 14.5f, R.color.text2).apply { setLineSpacing(0f, 1.15f); setTextIsSelectable(true) })
        }
    }
}

object Privacy {
    const val DATE = "30.10.2026"

    val RU = listOf(
        "" to "Дата вступления в силу: $DATE\n\nMaysk Scan — приложение для сканирования документов, распознавания текста, работы с PDF и QR-кодами. Мы создали его так, чтобы ваши документы оставались только у вас.",
        "1. Какие данные обрабатываются" to "Приложение работает с тем, что вы сами в него добавляете: снимки камеры, фото из галереи, PDF и другие файлы, распознанный текст, подписи, QR-коды. Всё это обрабатывается прямо на телефоне. Мы не собираем имя, телефон, email, контакты или местоположение. Регистрации и учётных записей нет.",
        "2. Где хранятся ваши файлы" to "Документы, история и подписи хранятся во внутренней памяти приложения на вашем телефоне. Если вы выбрали в настройках «Копии в папку», копии файлов сохраняются в эту папку. Файлы никуда не отправляются, пока вы сами не нажмёте «Поделиться». Удалить файлы можно в Истории; при удалении приложения удаляются все его данные.",
        "3. Разрешения" to "Камера — только для сканирования документов и QR-кодов, снимки не передаются. Интернет — только для проверки обновлений приложения и для однократной загрузки языковых пакетов перевода. Отпечаток пальца проверяет сама система Android: приложение не получает и не хранит отпечатки. PIN-код блокировки хранится в зашифрованном виде (хэш) только на телефоне.",
        "4. Интернет-запросы" to "Проверка обновлений: приложение запрашивает с нашего сервера номер последней версии. Запрос не содержит ваших данных и документов; сервер хостинга может технически записывать IP-адрес в журналы.\n\nСервисы Google: сканер документов, распознавание штрихкодов, определение языка и перевод используют компоненты Google ML Kit и Google Play services. Они работают на устройстве, но Google может загружать модели и собирать обезличенную техническую статистику их работы по своей политике: policies.google.com/privacy. Изображения документов и текст при этом в Google не отправляются.",
        "5. Реклама и аналитика" to "В приложении нет рекламы, сторонней аналитики и трекеров. Мы не продаём и не передаём данные третьим лицам.",
        "6. Дети" to "Приложение не предназначено специально для детей и не собирает сведений о них.",
        "7. Изменения" to "Если политика изменится, новая версия появится в приложении (Настройки → Политика конфиденциальности) с новой датой.",
        "8. Связь" to "Вопросы о конфиденциальности можно задать по контактам разработчика, указанным на странице Maysk Scan в магазине приложений.",
    )

    val KK = listOf(
        "" to "Күшіне енген күні: $DATE\n\nMaysk Scan — құжаттарды сканерлеуге, мәтінді тануға, PDF және QR-кодтармен жұмыс істеуге арналған қосымша. Құжаттарыңыз тек өзіңізде қалатындай етіп жасалған.",
        "1. Қандай деректер өңделеді" to "Қосымша сіз өзіңіз қосқан нәрселермен жұмыс істейді: камера суреттері, галереядағы фото, PDF және басқа файлдар, танылған мәтін, қолтаңбалар, QR-кодтар. Мұның бәрі телефонның өзінде өңделеді. Біз атыңызды, телефоныңызды, email, контактілерді немесе орналасқан жеріңізді жинамаймыз. Тіркелу және аккаунт жоқ.",
        "2. Файлдарыңыз қайда сақталады" to "Құжаттар, тарих және қолтаңбалар телефондағы қосымшаның ішкі жадында сақталады. Баптауларда «Қалтаға көшірме» таңдалса, файлдардың көшірмесі сол қалтаға сақталады. «Бөлісу» батырмасын өзіңіз баспайынша, файлдар ешқайда жіберілмейді. Файлдарды Тарихтан жоюға болады; қосымшаны өшіргенде оның барлық деректері жойылады.",
        "3. Рұқсаттар" to "Камера — тек құжаттар мен QR-кодтарды сканерлеу үшін, суреттер ешқайда берілмейді. Интернет — тек жаңартуларды тексеру және аударма тіл пакеттерін бір рет жүктеу үшін. Саусақ ізін Android жүйесінің өзі тексереді: қосымша саусақ ізін алмайды және сақтамайды. Құлыптың PIN-коды тек телефонда шифрланған түрде (хэш) сақталады.",
        "4. Интернет сұраулары" to "Жаңартуларды тексеру: қосымша біздің серверден соңғы нұсқаның нөмірін сұрайды. Сұрауда сіздің деректеріңіз бен құжаттарыңыз жоқ; хостинг сервері IP-мекенжайды журналға техникалық түрде жазуы мүмкін.\n\nGoogle қызметтері: құжат сканері, штрихкодты тану, тілді анықтау және аударма Google ML Kit пен Google Play services құрамдас бөліктерін пайдаланады. Олар құрылғыда жұмыс істейді, бірақ Google өз саясатына сәйкес модельдерді жүктеп, жұмысы туралы жеке басты анықтамайтын техникалық статистика жинауы мүмкін: policies.google.com/privacy. Құжат суреттері мен мәтін Google-ға жіберілмейді.",
        "5. Жарнама және аналитика" to "Қосымшада жарнама, бөгде аналитика және трекерлер жоқ. Біз деректерді сатпаймыз және үшінші тұлғаларға бермейміз.",
        "6. Балалар" to "Қосымша арнайы балаларға арналмаған және олар туралы мәлімет жинамайды.",
        "7. Өзгерістер" to "Саясат өзгерсе, жаңа нұсқасы жаңа күнімен қосымшада пайда болады (Баптаулар → Құпиялылық саясаты).",
        "8. Байланыс" to "Құпиялылық туралы сұрақтарды қосымшалар дүкеніндегі Maysk Scan бетінде көрсетілген әзірлеуші байланыстары арқылы қоюға болады.",
    )

    val EN = listOf(
        "" to "Effective date: $DATE\n\nMaysk Scan is an app for scanning documents, recognizing text and working with PDF files and QR codes. It is built so that your documents stay with you.",
        "1. What data is processed" to "The app works only with what you add to it: camera shots, photos from your gallery, PDF and other files, recognized text, signatures and QR codes. All of it is processed on your phone. We do not collect your name, phone number, email, contacts or location. There is no registration and no account.",
        "2. Where your files are stored" to "Documents, history and signatures are stored in the app's internal storage on your phone. If you choose «Copies to folder» in Settings, copies are also saved to that folder. Files are never sent anywhere unless you tap «Share» yourself. You can delete files in History; uninstalling the app removes all of its data.",
        "3. Permissions" to "Camera — only to scan documents and QR codes; photos are not transmitted. Internet — only to check for app updates and to download translation language packs once. Fingerprints are verified by Android itself: the app never receives or stores them. The lock PIN is stored only on the phone as a hash.",
        "4. Network requests" to "Update check: the app asks our server for the latest version number. The request contains none of your data or documents; the hosting provider may technically log the IP address.\n\nGoogle services: the document scanner, barcode recognition, language identification and translation use Google ML Kit and Google Play services components. They run on the device, but Google may download models and collect anonymous technical statistics about their operation under its own policy: policies.google.com/privacy. Document images and text are not sent to Google.",
        "5. Ads and analytics" to "The app has no ads, no third-party analytics and no trackers. We do not sell or share data with third parties.",
        "6. Children" to "The app is not specifically directed at children and does not collect information about them.",
        "7. Changes" to "If this policy changes, the new version will appear in the app (Settings → Privacy policy) with a new date.",
        "8. Contact" to "Privacy questions can be sent to the developer contacts listed on the Maysk Scan page in the app store.",
    )
}
