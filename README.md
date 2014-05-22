Photon
======
Small apllication that lets you fire tens of thousends concurrent http reuquests from a single computer.
To do this photon spawns lightweigt thread  instead of real threads for each connection. Photon is based on [quasar](http://github.com/puniverse/quasar) and [comsat](http://github.com/puniverse/comsat) libraries.

## Usage
Download [photon.jar](https://github.com/puniverse/photon/releases). This is [capsule](http://github.com/puniverse/capsule) jar so you can simply run it by:
```
usage: java -jar photon.jar [options] url
 -duration <arg>         test duration in seconds
 -help                   print help
 -maxconnections <arg>   maximum number of open connections
 -rate <arg>             requests per second
 -timeout <arg>          connection and read timeout in millis
``` 
Enjoy !
