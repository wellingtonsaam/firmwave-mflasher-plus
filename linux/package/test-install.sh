#!/bin/sh
# Installs a package in a clean container of a distribution with its own
# package manager (so its dependencies resolve), then checks that M-Flasher +
# runs and its files are in place. For CI:
#   docker run --rm -v "$PWD:/w" -w /w debian:12 linux/package/test-install.sh m-flasher-plus_*.deb
# Run on a machine of your own, it doubles as a check of what the window needs:
# it names every library that is missing, and the command that installs them.
set -eu
pkg=$1
if command -v apt-get >/dev/null; then
    mgr=apt
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq "./$pkg" >/dev/null
elif command -v dnf >/dev/null; then
    mgr=dnf
    dnf install -y -q "./$pkg"
elif command -v zypper >/dev/null; then
    mgr=zypper
    zypper --non-interactive --quiet install --allow-unsigned-rpm "./$pkg"
else
    echo "no known package manager" >&2
    exit 1
fi
# It has to run, and run clean. A package built on a newer system than it says
# it supports still installs -- the dependency is on the glibc the build
# machine had, not the one the program ended up asking for -- and the loader
# then warns about versions that are not there on every start. That is for the
# build to fix, not for whoever installs the package to discover.
out=$(m-flasher-plus --help 2>&1) || { printf '%s\n' "$out" >&2; echo "m-flasher-plus --help failed" >&2; exit 1; }
printf '%s\n' "$out"
case $out in *"not found (required by"*)
    echo "built against a newer system than this package declares: the loader is missing library versions above" >&2
    exit 1 ;;
esac
# (not the man page: minimal images such as Fedora's and Ubuntu's leave documentation out)
for f in /usr/bin/m-flasher-plus /usr/lib/udev/rules.d/70-m-flasher-plus.rules /usr/lib/modules-load.d/m-flasher-plus.conf \
    /usr/share/applications/io.github.wellingtonsaam.m-flasher-plus.desktop \
    /usr/share/metainfo/io.github.wellingtonsaam.m-flasher-plus.metainfo.xml \
    /usr/share/icons/hicolor/256x256/apps/io.github.wellingtonsaam.m-flasher-plus.png; do
    test -f "$f" || { echo "missing: $f" >&2; exit 1; }
done

# The window opens its graphics libraries with dlopen when it starts, so no
# packaging tool can see them in the executable and nothing fails until it is
# run. Every one is listed here with what carries it, and all that are missing
# are reported together, with the command that installs them, rather than one
# at a time. A missing dependency fails; a missing recommended package is said
# and allowed, because an image built to be small (openSUSE's, Fedora's)
# leaves weak dependencies out, and which of them a system needs depends on
# its desktop anyway.
#
#   library | Debian/Ubuntu package | rpm capability | kind
libs="\
libEGL.so.1|libegl1|libEGL.so.1()(64bit)|dependency
libxkbcommon.so.0|libxkbcommon0|libxkbcommon.so.0()(64bit)|dependency
libGL.so.1|libgl1|libGL.so.1()(64bit)|recommended
libwayland-client.so.0|libwayland-client0|libwayland-client.so.0()(64bit)|recommended
libwayland-egl.so.1|libwayland-egl1|libwayland-egl.so.1()(64bit)|recommended
libxkbcommon-x11.so.0|libxkbcommon-x11-0|libxkbcommon-x11.so.0()(64bit)|recommended
libX11.so.6|libx11-6|libX11.so.6()(64bit)|recommended
libX11-xcb.so.1|libx11-xcb1|libX11-xcb.so.1()(64bit)|recommended
libXcursor.so.1|libxcursor1|libXcursor.so.1()(64bit)|recommended
libXi.so.6|libxi6|libXi.so.6()(64bit)|recommended
libXrandr.so.2|libxrandr2|libXrandr.so.2()(64bit)|recommended"

cache=$(ldconfig -p)
missing_report=
missing_pkgs=
short=
while IFS='|' read -r lib deb rpmcap kind; do
    [ -n "$lib" ] || continue
    case $cache in *"$lib"*) continue ;; esac
    if [ "$mgr" = apt ]; then want=$deb; else want=$rpmcap; fi
    missing_report="$missing_report  $lib  ($kind, in $want)
"
    missing_pkgs="$missing_pkgs '$want'"
    [ "$kind" = dependency ] && short=yes
done <<LIBS
$libs
LIBS

if [ -n "$missing_pkgs" ]; then
    printf 'the window needs libraries this system does not have:\n%s' "$missing_report" >&2
    case $mgr in
        apt)    printf 'install them with:\n  sudo apt install%s\n' "$missing_pkgs" >&2 ;;
        dnf)    printf 'install them with:\n  sudo dnf install%s\n' "$missing_pkgs" >&2 ;;
        zypper) printf 'install them with:\n  sudo zypper install%s\n' "$missing_pkgs" >&2 ;;
    esac
    [ -n "$short" ] && exit 1
fi
echo "OK: $(. /etc/os-release; echo "$PRETTY_NAME")"
