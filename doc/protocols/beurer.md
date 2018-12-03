Beurer
======

General
-------

* `<sb>`: start byte (e7 or f7)
* `<alt sb Y>`: alternative start byte (eY or fY)
* `<uid>`: user ID (8 bytes, BE)
* `<name>`: 3 characters (bytes)
* `<timestamp>`: unix timestamp (4 bytes)
* `<weight>`: 2 bytes, BE (unit g / 50)
* `<impedance>`: 2 bytes, BE

All protocol bytes are in hex. Writes are done to characteristic
0xffe1 on the service 0xffe0. This is also where notifications are
received from.


Initialization
--------------

1. Enable notifications.
2. Write: `<alt sb 6> 01`
3. Notification: `<alt sb 6> 00 20`
4. Write: `<alt sb 9> <timestamp>`
5. Write: `<sb> 4f <uid>`
6. Notification: `<sb> f0 4f <??> <battery> <wthr> <fthr> <unit> <ue> <urwe> <ume> <version>`

`<uid>` can be given as all 0 (or other invalid user id) to query
scale status only.

* `<battery>`: battery level
* `<wthr>`: weight threshold (unit g / 100)
* `<fthr>`: fat threshold
* `<unit>`: 1 = kg, 2 = lb, 4 = st
* `<ue>, <urwe>, <ume>`: user (reference weight, measurement) exists
* `<version>`: scale version


### Configure threshold

1. Write: `<sb> 4e <wthr> <fthr>`
2. Notification: `<sb> f0 4e 00`

Thresholds in original app:
* 0x28 0xdc (4.0 22.0)
* 0x14 0xdc (2.0 22.0)
* 0x0a 0x14 (1.0 2.0)

After scale reset: 0x14 0x14 (2.0 2.0)


### Set unit

1. Write: `<sb> 4d <unit>`
2. Notification: `<sb> f0 4d 00`


### Force disconnect

1. Write: `<alt sb a> 2`


Users
-----

### Request user list

1. Write: `<sb> 33`
2. Notification: `<sb> f0 33 00 <count> <max>`
3. Notification: `<sb> 34 <count> <current> <uid> <name> <year>`
4. Write: `<sb> f1 34 <count> <current>`
5. Goto 3 if `<count> != <current>`


### Get user info

1. Write: `<sb> 36 <uid>`
2. Notification: `<sb> f0 36 00 <name> <year> <month> <day> <height> <sex|activity>`

* `<month>`: January == 0
* `<sex|activity>`: sex (female = 0x00, male = 0x80) | activity level 1 - 5


### Add new user

1. Write: `<sb> 31 <uid> <name> <year> <month> <day> <height> <sex|activity>`
2. Notification: `<sb> f0 31 00`
3. Perform initial measurement.

Status in notification: 00 (ok), 01 (full), 02 (uid taken), 03 (name used)

### Update user

1. Write: `<sb> 35 <uid> ... (same as when adding user)`
2. Notification: `<sb> f0 35 00`


### Delete user

1. Write: `<sb> 32 <uid>`
2. Notification: `<sb> f0 32 00`


Measurements
------------

### Perform measurement

1. Write: `<sb> 40 <uid>`
2. Notification: `<sb> f0 40 00`
3. Notification: `<sb> 58 <status> <weight>`
4. Write: `<sb> f1 58 <status> <weight MSB>`
5. Goto 3 if `<status> != 0`
6. Notification: `<sb> 59 <count> <current> 01 <uid>`
7. Write: `<sb> f1 59 <count> <current>`
8. Notification: `<sb> 59 <count> <current> <11 bytes data>`
9. Write: `<sb> f1 59 <count> <current>`
10. Goto 8 if `<count> != <current>`

* Step 1 selects the user with the given ID.
* `<status>`: 0 for stable measurement, 1 otherwise
* All `<data>` from step 8 is joined and parsed as a measurement.


### Request saved measurements

1. Write: `<sb> 41 <uid>`
2. Notification: `<sb> f0 41 <count> 00`
3. Notification: `<sb> 42 <count> <current> <11 bytes data>`
4. Write: `<sb> f1 42 <count> <current>`
5. Goto 3 if `<count> != <current>`

* All `<data>` from step 3 is joined and parsed as `<count> / 2` measurement(s).


### Delete saved measurements

1. Write: `<sb> 43 <uid>`
2. Notification: `<sb> f0 43 00`


### Measurement

22 bytes of data:

`<timestamp> <weight> <impedance> <fat> <water> <muscle> <bone>
<BMR> <AMR> <BMI>`


Unknown measurements
--------------------

### Request unknown measurements

1. Write: `<sb> 46`
2. Notification: `<sb> f0 46 00`
3. Notification: `<sb> 47 <count> <current> <mem> <timestamp> <weight> <impedance>`
4. Write: `<sb> f1 47 <count> <current>`
5. Goto 3 if `<count> != <current>`

* `<mem>`: index in memory (stays constant after removal)


### Assign unknown measurement

1. Write: `<sb> 4b <uid> <timestamp> <weight> <impedance> <mem>`
2. Notification: `<sb> f0 4b 00`
3. Notification: `<sb> 4c <count> <current> <11 bytes data>`
4. Write: `<sb> f1 4c <count> <current>`
5. Goto 3 if `<count> != <current>`

* All `<data>` from step 3 is joined and parsed as a measurement.


### Delete unknown measurement

1. Write: `<sb> 49 <mem>`
2. Notification: `<sb> f0 49 00`
