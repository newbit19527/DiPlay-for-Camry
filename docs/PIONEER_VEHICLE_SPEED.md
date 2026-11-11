# Pioneer vehicle speed for iPhone location

The optional **Vehicle speed to iPhone (experimental)** control in Navigation → Location uses the
Pioneer CEAM sensor service alongside Android positioning. It defaults to off, requires location
reporting, and takes effect on the next CarPlay connection. It is offered only with the Camry
adaptation enabled. The iPhone must request the vehicle-speed location component before the
subscription starts. Missing vendor APIs disable the vehicle-speed capability; runtime failures
stop the speed source without replacing the GPS source or sending zero as a fallback.

## Firmware contract

The reference is the Camry NVF-9108ZT firmware's CEAM framework and bundled navigation adapters:

- `jp.pioneer.ceam.handler.GpsChipsController.registerListener(WeakReference)` subscribes to sensor
  events; `removeListener` removes that same reference. The application retains the listener strongly.
- `GpsChipsListener.OnSpeedChangedForJson(String)` supplies `value`, `interval`, and `tickTime`.
  The reference firmware multiplies signed `GC_Speed.speed` by `3.6f` into JSON `value` (km/h).
  `PioneerVehicleSpeedSource` divides this value by 3.6 once, before the m/s buffer and its validity
  checks. Negative values remain negative and are rejected; they are not converted to a forward
  speed with `abs`. Earlier documentation incorrectly read the DEX `0xA8 mul-float` as division.
  `NaviSensorProxy` forwards the original vendor JSON to the bundled
  Amap service as `KEY_STRING_PULES` (1073741832), which consumes it as motion input independently
  of GPS fixes.
- `jp.pioneer.ceam.VehicleInfo.VehicleInfo.getShiftStatus()` returns `IShiftStatus.status` and
  `value`. The original `VehicleBasicInfoControl.getGear()` accepts only status 1, maps raw values
  1/2/3/4 to PAL constants PARK/REVERSE/NEUTRAL/DRIVE (4/2/1/8), and otherwise returns UNKNOWN.
  The new source maps these raw selectors to P/R/N/D. Speed zero or a reverse flag is not a selector.

Reference binary SHA-256:

| Input | SHA-256 |
|---|---|
| CEAM framework extracted DEX | `72b3c82404a36bca9fae57397024b0fe4245c1d5a599055a36b3ac5e122424a8` |
| ThirdPartyService.odex | `60103b07f91ca3347587e60fe5ace50ed083d5471c8c5fb2d2b83470ea655614` |
| AMapAuto.apk 6.1.7.601573 | `26ae1e418b4e24ce4e01c19ea70cbd9f15511ae56320e67f8d47905fe31587d0` |

Vendor firmware, decompiled code, and private vehicle logs are not included in the source change.
Compile-only CEAM declarations supply the listener descriptor and are not application runtime classes.
The reference arithmetic and recorded speed/GPS comparisons support this unit interpretation;
matching installed CEAM/native hashes have been checked on one reference head unit. This does not establish compatibility with other firmware or iPhone parsing/fusion.

## Execution and validity

`PioneerVehicleSpeedSource` uses separate event and selector workers. Controller initialization,
callbacks and unregistration use one process-owned sensor HandlerThread. The CEAM singleton binds
its global dispatcher to the first caller's Looper (`myLooper`, falling back to the main Looper),
so this thread stays alive for the process lifetime. Session stop unregisters its own listener;
it must not kill the SDK's dispatcher. A separate per-run HandlerThread reads the selector 250 ms
apart. A slow selector query therefore cannot block sensor callbacks. Selector age starts at the
query's start, not its completion. The iAP2 loop only drains a bounded buffer and never waits on a vendor call. This is an ordinary read-only SDK client: no firmware write, root
operation, ADB command, or new IPC service is introduced.

After input normalization, each received speed requires finite data in 0–100 m/s, positive interval, a non-negative advancing
native tick, and a valid selector observed within one second. Invalid selectors or selector changes
clear pending samples. Moving data above 0.5 m/s in PARK is rejected. At most 32 samples are queued;
measurements older than two seconds are discarded. Stop immediately disables draining and clears
state, then unregisters on the event worker and retires the selector worker. A cancelled
initialization cannot publish samples. A new START received during cleanup waits for both lanes'
cleanup and resumes afterwards; another STOP revokes that pending restart. Exceptions or missing
native dependencies disable this run rather than repeatedly restarting a broken SDK. Removing
vehicle speed from a later location request also stops the existing speed subscription.

Native `tickTime` is used for ordering and duplicate rejection. Its time unit/epoch has not been
validated against Android's clock, so outgoing timestamps use callback receipt time from
`elapsedRealtime`. This bounds **receipt age**, not hardware acquisition age; scheduling/SDK backlog
and firmware-internal caching still require on-device measurement. The vendor getter is synchronous
and has no exposed cancellation; a stuck getter may delay the selector lane's cleanup, but cannot
block sensor unregistration, the iAP2, UI, audio, or video thread. No replacement selector worker is
spawned for that same source until cleanup completes.

The existing `VehicleSpeedLocationProvider` batches measured samples into `$PASCD` once the iPhone
requests location component 4. GPS GGA/RMC reporting stays on the existing path; a speed-only PASCD
can be sent without a position fix. BYD keeps its existing source and behavior. This change does not
modify the PASCD format or assume that a one-second reporting interval implies one-second sampling.

## Validation boundary

JVM tests cover selector mapping/validity, unit preservation, sample batching, direction changes,
duplicate and regressing ticks, invalid numbers, stale data, bounded storage, and reset. Deterministic
worker tests cover stop/start ordering, cancellation during registration, failed registration,
missing native dependencies, slow selector queries, and dispatcher survival across reconnects.
Location tests cover request gating, subscription withdrawal, and speed-only output. UI checks cover explicit persistence and
compact/full/RTL rendering. These checks do not establish native permission, actual sensor cadence,
selector freshness inside the vehicle service, iPhone acceptance, or navigation accuracy in tunnels.
Keep the option off until a physical trial has checked those points on the intended head unit.

## CEAM selector property subscription

On the verified Camry firmware, `VehicleInfo` initially fills its cache with `getProperties`.
Ongoing property delivery requires its normal SDK subscription. The speed backend requests
`VehicleInfo.registerListener(null)` once per process-owned singleton, on the selector worker.
The null argument preserves the SDK's existing UI listener while subscribing the singleton as
its property callback. The SDK's service-reconnect path resubscribes. CarPlay STOP does not call
`unregisterListener`, which would unsubscribe the shared singleton and clear another UI owner.
A failed request stops the speed run; only a later explicit START can retry. A successful SDK subscription request is not proof of hardware freshness; the P/negative-speed,
range, tick and age protections remain in place. Stationary P→R→P cache updates were verified on the reference head unit. Full PRND and reverse-motion validation remain outstanding.

## Reference vehicle observations and remaining acceptance

Historical nonzero samples from an earlier build matched the native value at the same tick after exactly one km/h-to-m/s conversion. The latest diagnostic build verified stationary P→R→P updates and 2,050 zero-speed samples; 202 speed-only batches returned successfully from the local transport. These are separate observations, not a nonzero drive test of this extracted candidate.

This candidate still requires a normal nonzero drive and continuity check. iPhone receipt/fusion, navigation behavior under bridges or in tunnels, and reverse motion remain unverified. The extracted patch has not been installed on a vehicle. Private logs, coordinates, firmware, APKs and signing/authentication material are excluded.
