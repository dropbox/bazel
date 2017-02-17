#!/bin/bash -e

pkg_dir=$(mktemp -d ./tmp-bazel.XXXX)

function cleanup() {
  if [[ "$DEBUG" == "" ]]; then
    rm -rf $pkg_dir
  fi
}

function fail() {
  echo "$@" >&2
  exit 1
}

trap cleanup EXIT

mkdir -p $pkg_dir/usr/bin $pkg_dir/etc/bash_completion.d

# Do some hacking to support multiple java installations.
cp ./output/bazel $pkg_dir/usr/bin/bazel-bin
cp bazel.sh $pkg_dir/usr/bin/bazel
chmod +x $pkg_dir/usr/bin/bazel

cp bazel.bazelrc $pkg_dir/usr/bin/bazel-bin.bazel-binrc

./output/bazel --batch build scripts:bash_completion
cp ./bazel-bin/scripts/bazel-complete.bash $pkg_dir/etc/bash_completion.d/bazel || fail "Can't package bash_completion"

# oracle-java8-jdk
# oracle-java8-installer
timestamp=$(python -c "import time; print time.strftime('%Y%m%d%H%M%S', time.gmtime(time.time()))")
fpm -d oracle-java8-jdk --verbose --debug --prefix / -C $pkg_dir -s dir -t deb -n "bazel" --provides bazel --conflicts bazel-beta --replaces bazel-beta -v 1.0.$timestamp usr etc

# Beta package
fpm -d oracle-java8-jdk --verbose --debug --prefix / -C $pkg_dir -s dir -t deb -n "bazel-beta" --provides bazel --conflicts bazel --replaces bazel -v 1.0.$timestamp usr etc
