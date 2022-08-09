# Firebird driver for metabase

This driver enables metabase to connect to [FirebirdSQL](https://firebirdsql.org/) databases.

## Installation:

* Make sure you have installed a recent Metabase Version.
* Download the [latest release](https://github.com/evosec/metabase-firebird-driver/releases/latest) of the Firebird driver or [build it from source](#building-from-source).
* Create the `plugins` directory if it doesn't already exist. By default that directory is next to the metabase.jar file, but you can specify a different directory by setting the environment varianble `MB_PLUGINS_DIR`. 
* Just drop the `firebird.metabase-driver.jar` in the plugins directory. On startup, metabase will load the plugin and the driver should be available.

## Authentication issues when using Legacy_Auth

The latest releases are built with version 4.x of Jaybird (the Firebird JDBC driver), [which no longer supports Legacy_Auth](https://www.firebirdsql.org/file/documentation/drivers_documentation/java/4.0.0/release_notes.html#removed-legacy_auth-from-default-authentication-plugins).

:warning: First of all: Legacy_Auth is disabled for a reason. You should only use the following workarounds if you have no way of using a more secure authentication method. :warning:

If you really need to access your database using Legacy_Auth ([#14](https://github.com/evosec/metabase-firebird-driver/issues/14)) you can [add it to the authentication plugins](https://www.firebirdsql.org/file/documentation/drivers_documentation/java/4.0.0/release_notes.html#configure-authentication-plugins). For example:
```
jdbc:firebirdsql://localhost/employee?authPlugins=Legacy_Auth
```

If that does not work for you, you can use the release artifact `firebird.metabase-driver_jaybird-3.jar` which is built with Jaybird 3.x.

## Building from source:

For a detailed description, take a look at the [official documentation](https://github.com/metabase/metabase/wiki/Writing-A-Driver).

#### Prepare a local Metabase installation for building drivers

* Download the Metabase sources
* Compile a local Metabase installation for building drivers
```
lein install-for-building-drivers
```

#### Build the driver

* Checkout the Firebird driver sources to `{metabase-source-dir}/modules/drivers/firebird`
* Build the driver. This will create the .jar file in the directory `target/uberjar`. Just copy that file to your plugins directory and you are good to go!
```
cd {metabase-source-dir}/modules/drivers/firebird
lein clean
LEIN_SNAPSHOTS_IN_RELEASE=true DEBUG=1 lein uberjar
```
