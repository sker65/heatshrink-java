# heatshrink-java
Java implementation of heatshrink compression algorithm

[![Build Status](https://travis-ci.org/sker65/heatshrink-java.svg?branch=master)](https://travis-ci.org/sker65/heatshrink-java)
[![Coverage Status](https://coveralls.io/repos/github/sker65/heatshrink-java/badge.svg?branch=master)](https://coveralls.io/github/sker65/heatshrink-java?branch=master)

## Why
This library is a java port from the great heatshrink library by Scott Vokes
https://spin.atomicobject.com/2013/03/14/heatshrink-embedded-data-compression/
that runs great on micro controllers because of very little memory usage.

When decompressing on an mcu you sometimes need to create compressed data on
a normal PC, this is where this library could be used.

## C-Sources
Original c sources can be found here https://github.com/atomicobject/heatshrink

