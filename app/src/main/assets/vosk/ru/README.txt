Сюда можно положить модель Vosk, чтобы она попала в APK.

Как встроить модель в сборку:
1. Скачайте vosk-model-small-ru-0.22.zip (около 45 МБ) с https://alphacephei.com/vosk/models
2. Распакуйте архив.
3. Скопируйте содержимое папки vosk-model-small-ru-0.22 сюда, чтобы получилось:
   app/src/main/assets/vosk/ru/am/...
   app/src/main/assets/vosk/ru/conf/model.conf
   app/src/main/assets/vosk/ru/graph/...
   app/src/main/assets/vosk/ru/ivector/...
4. Пересоберите APK. Приложение при первом запуске само развернёт модель во внутреннюю память.

Если этой папки нет, приложение остаётся рабочим: модель можно импортировать на телефоне
через экран «Модель распознавания» → «Импортировать модель (.zip)».
