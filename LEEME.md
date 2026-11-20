# Renotas, by Vincent Calavera

Proyecto Android de Renotas, listo para compilar. La interfaz está en
`app/src/main/assets/index.html`. El código de Android (avisos, dictado de voz)
está en `app/src/main/java/cl/vincentcalavera/renotas/`.

## Sacar el APK con Android Studio (lo más fácil)

1. Instala Android Studio (gratis): https://developer.android.com/studio
2. Abre Android Studio y elige **Open**. Selecciona esta carpeta (`Renotas`).
3. Espera a que termine "Gradle sync". La primera vez descarga cosas y demora unos minutos.
4. Menú **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Cuando termine, toca **locate** en el aviso. El archivo queda en:
   `app/build/outputs/apk/debug/app-debug.apk`
6. Pasa ese archivo al teléfono, ábrelo y acepta "instalar apps de origen desconocido".

## Con línea de comandos

Necesitas JDK 17 y el Android SDK (variable `ANDROID_HOME`):

    ./gradlew assembleDebug

## Qué incluye

- Pantalla de inicio animada con tu logo y "By Vincent Calavera".
- Recordatorios con aviso real del teléfono (notificación con sonido y vibración,
  aunque la app esté cerrada). Se reprograman al reiniciar el teléfono.
- Dictado por voz con el reconocimiento de voz de Android (botón del micrófono en Ideas).
- Icono de Renotas como ícono de la app.
- Campana flotante sobre otras apps y la pantalla de inicio, con el número de pendientes
  (se activa desde la tarjeta "Campana flotante" en Inicio; pide el permiso
  "Mostrar sobre otras apps"). Se arrastra y se pega a un lado; al tocarla abre Renotas.

## Permisos que va a pedir

- Notificaciones (Android 13 o superior).
- Micrófono, la primera vez que uses el dictado.
- Mostrar sobre otras apps, para la campana flotante.
- Alarmas exactas (Android 12 o superior), para que el aviso llegue justo a la hora.

## Pendiente para una próxima versión

- Para publicar en Google Play hay que firmar el APK con tu propia llave.
