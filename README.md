# Photon

A small HTTP load testing app that lets you fire tens of thousends concurrent HTTP reuquests from a single computer. 
To do this photon spawns a fiber (lightweigt thread) instead of a real thread for each connection. Photon is based on [Quasar](http://github.com/puniverse/quasar) and [Comsat](http://github.com/puniverse/comsat).

## Usage

Download [photon.jar](https://github.com/puniverse/photon/releases). This is a [capsule](http://github.com/puniverse/capsule) jar so you can simply run it by:

```
usage: java -jar photon.jar [options] url
 -duration <arg>         test duration in seconds
 -help                   print help
 -maxconnections <arg>   maximum number of open connections
 -rate <arg>             requests per second
 -timeout <arg>          connection and read timeout in millis
``` 

## License

```
Copyright 2014 Parallel Universe Software Co.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

<http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```