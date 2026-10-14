# monosync

Android-застосунок (Kotlin, Jetpack Compose) для автоматичного обліку операцій Monobank.

- `core/`: чиста логіка на Kotlin (розбір сповіщень, гроші, MCC) з юніт-тестами.
- `app/`: застосунок Android (Room, WorkManager, Monobank API, Notion API, Compose UI).
- APK збирає й публікує GitHub Actions (`.github/workflows/android.yml`). Ключ підпису береться із секрету `MONOSYNC_KEYSTORE_B64` і в репозиторії не зберігається.

## Правила

- Після кожної зміни функціоналу онови `README.md`: короткий опис застосунку й список можливостей, без технічних деталей.
- Тексти інтерфейсу пишуться українською.
- Не коміть ключі, токени та email-адреси.
