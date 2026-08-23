# Publicar este fork en F-Droid

## ¿GitHub o GitLab?

**Tu código se queda en GitHub.** F-Droid compila desde cualquier repositorio git;
no le importa dónde esté alojado.

GitLab hace falta **solo para enviar la solicitud**, porque `fdroiddata` — el
repositorio donde vive la metadata de todas las apps — está en
`gitlab.com/fdroid/fdroiddata`. Necesitas una cuenta de GitLab, pero nada más:
no tienes que mover, duplicar ni espejar el código.

Hay dos vías, y las dos pasan por GitLab:

| vía | esfuerzo tuyo | velocidad |
|---|---|---|
| **Submission Queue** — abres un ticket y el equipo escribe la metadata | bajo | lenta |
| **Merge request** — escribes tú la metadata y la propones | alto | mucho más rápida |

La segunda es más rápida precisamente porque le quitas trabajo al revisor. Si
eliges esa, asumes la responsabilidad de que la metadata sea correcta.

Detalles que cuestan un rechazo si se pasan por alto:

- El título del MR debe seguir el formato `New app: <nombre>`.
- Tu fork de `fdroiddata` tiene que ser **público** y la rama **no protegida**,
  o no pueden hacer rebase antes de fusionar.
- Cada release debe tener un **tag** en git. Si `versionName` es `1.0`, hace
  falta un tag `v1.0`. No se acepta apuntar a una rama.
- No mandes metadata generada con `fdroid import` tal cual.

## El problema real de esta app

F-Droid compila **todo** desde fuente y rechaza binarios ya compilados metidos en
el repositorio. Aquí hay dos:

| qué | dónde | de dónde sale |
|---|---|---|
| `libusque.so` (13 MB) | `app/src/main/jniLibs/arm64-v8a/` | `github.com/paulvers-ui/usque` (fork de diniboy1123/usque, Go, MIT) |
| `firestack.aar` | `app/libs/` | `github.com/paulvers-ui/firestack`, rama `n2` (fork de celzero/firestack, Go, MPL-2.0) |

**Ojo con la rama de firestack.** El fork tiene varias ramas históricas; solo
`n2` en `paulvers-ui/firestack` lleva la API correcta (`OnQuery(uid, domain
*Gostr, qtyp int)`, sin el parámetro `who` que añadió upstream y sin perder
`Gostr`/`Gobyte`). Si algún día limpias ese repo, es esa rama la que hay que
conservar.

Que ambos sean FOSS y con fuente pública es lo que hace esto **posible**. Si
fueran binarios sin fuente, no habría nada que hacer.

La receta en `com.creatore.rethinkfork.yml` los borra (`rm:`) y los reconstruye
(`prebuild:`) desde `srclibs`. Ese es todo el truco.

Además hay que forzar `firestackRepo=local`: JitPack **no** está entre los
repositorios de dependencias que F-Droid permite (solo Maven Central, Google
Maven y OSS Sonatype), así que resolver desde ahí es rechazo directo.

Lo que **no** es problema:

- Firebase y Crashlytics solo están en los sabores `website` y `play`. El sabor
  `fdroid` está limpio, y por eso la receta compila `fdroidFull`.
- El `applicationId` ya es distinto del original (`com.creatore.rethinkfork`),
  requisito obligatorio para un fork.
- Licencia Apache-2.0, repositorio público.

## srclibs

`srclibs:` no admite una URL suelta: cada entrada apunta a un fichero dentro de
`srclibs/` en tu fork de `fdroiddata`. Hay que crear dos:

`srclibs/usque.yml`
```yaml
RepoType: git
Repo: https://github.com/paulvers-ui/usque.git
```

`srclibs/firestack.yml`
```yaml
RepoType: git
Repo: https://github.com/paulvers-ui/firestack.git
```

Y en la receta se referencian fijando el commit: `usque@<sha>`, `firestack@<sha>`.
Fija **commits**, no ramas — una rama puede moverse y entonces la compilación
deja de ser reproducible, que es justo lo que F-Droid verifica. Para
`firestack@<sha>`, el commit tiene que venir de la rama `n2` de ese fork (ver
aviso arriba); un srclib no fija rama, solo commit, así que el SHA elegido
importa.

**usque no necesita NDK.** El `goreleaser.yml` de ese fork compila con
`CGO_ENABLED=0` — es Go puro cruzado a `GOOS=android GOARCH=arm64`, sin
compilador C. El paso `ndk:` de la receta sigue haciendo falta igualmente,
porque **firestack sí lo usa** (`gomobile bind` para generar el `.aar`).

## Pasos

**1. Poner un tag al release**

```bash
git tag v1.0.0 && git push origin v1.0.0
```

**2. Preparar fdroiddata**

```bash
# fork de gitlab.com/fdroid/fdroiddata desde la web, luego:
git clone --depth=1 https://gitlab.com/TU_CUENTA/fdroiddata ~/fdroiddata
cd ~/fdroiddata
git checkout -b com.creatore.rethinkfork
cp <este repo>/fdroid/com.creatore.rethinkfork.yml metadata/
# crear también srclibs/usque.yml y srclibs/firestack.yml (arriba)
```

**3. Rellenar los `<PLACEHOLDER>`** del `.yml`: versionName, commit del tag,
los dos SHA de srclibs, la descripción y la versión del NDK.

Puntos de partida verificados al escribir esto (comprueba que sigan siendo el
HEAD de cada rama antes de usarlos — esto cambia con cada push):

```
usque    (main): f1dab65
firestack (n2):  0159488
```

No los copies a ciegas: confirma el commit real justo antes de fijarlo, con
`git ls-remote https://github.com/paulvers-ui/usque.git main` y lo mismo para
`firestack.git n2`.

**4. Probarlo en el buildserver antes de mandar nada**

Este paso no es opcional. Si mandas un MR sin haber compilado, casi seguro
falla y se te va la cola de revisión en idas y venidas.

```bash
git clone --depth=1 https://gitlab.com/fdroid/fdroidserver ~/fdroidserver
sudo docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z \
  -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  registry.gitlab.com/fdroid/fdroidserver:buildserver

# dentro del contenedor:
. /etc/profile
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver"
export JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 > /dev/null \
  | grep 'java.home' | awk -F'=' '{print $2}' | tr -d ' ')
cd /build
fdroid readmeta
fdroid rewritemeta com.creatore.rethinkfork
fdroid lint com.creatore.rethinkfork
fdroid build com.creatore.rethinkfork
```

Si algo falla, edita el `.yml` y repite. Aquí es donde se ajusta el `prebuild:`
hasta que usque y firestack compilen de verdad — que es la parte que va a costar.

**5. Mandar el MR**

```bash
git add metadata/com.creatore.rethinkfork.yml srclibs/usque.yml srclibs/firestack.yml
git commit -m "New app: Rethink Dns Fork"
git push origin com.creatore.rethinkfork
```

MR contra `fdroiddata`, título `New app: Rethink Dns Fork`. Revisa que los
pipelines pasen y que el de build **produzca un APK**: si pasa pero no genera
APK, seguramente dejaste la compilación desactivada.

**6. Esperar**

Tras la fusión, entre 24 y 48 horas hasta aparecer en el repositorio, porque la
firma del APK requiere intervención humana.

## Sobre ser un fork

El original ya está publicado, así que espera esta pregunta: *¿qué aporta este
fork?* Ténlo escrito en `Description:`, no improvisado.

También conviene **avisar a los autores de Rethink** antes de enviar. F-Droid
espera que el autor original esté al corriente y no se oponga.

## Alternativas más baratas

Si el paso 4 se atasca — y es lo normal, `gomobile` en el buildserver es
puñetero — estas no exigen compilar desde fuente:

| opción | qué implica |
|---|---|
| **IzzyOnDroid** | repo F-Droid de terceros; acepta APK precompilados desde tus releases de GitHub. Los usuarios añaden una URL. |
| **Repo propio** | `fdroidserver` genera un repo estático; lo alojas en GitHub Pages. Control total, sin revisión. |
| **Obtainium** | instala directamente desde tus releases de GitHub. Configuración casi nula. |

Ninguna te da la visibilidad del repositorio principal, pero las tres funcionan
hoy y sin reescribir el build.
