# Example for building the driver with bash or similar

DRIVER_PATH=`pwd`

# switch to the local checkout of the Metabase repo
cd ../metabase

# get absolute path to the driver project directory

clojure \
  -Sdeps "{:aliases {:firebird {:extra-deps {evosec/firebird-driver {:local/root \"$DRIVER_PATH\"}}}}}"  \
  -X:build:firebird \
  build-drivers.build-driver/build-driver! \
  "{:driver :firebird, :project-dir \"$DRIVER_PATH\", :target-dir \"$DRIVER_PATH/target\"}"
