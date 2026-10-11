# HOTO JY001 Bluetooth protocol

This document describes how the HOTO Smart Laser Measure QWCJY001 (JY001, Mi model `hoto.etool.finder`) talks over Bluetooth Low Energy. It covers pairing, login and the measurement messages. Everything here was observed on one unit with firmware `1.1.1_0038`. Zamer implements it in `app/src/main/java/dev/bezh/zamer/ble/`.

The device uses Xiaomi's "standard auth" scheme. Pairing runs entirely between the phone and the device. No Xiaomi account, cloud call or internet access is involved.

## Advertising

| Field | Value |
|---|---|
| Local name | `xiaojiang.cc_9e2` |
| Service data UUID | `0xFE95` (MiBeacon) |
| Product ID | `0x09E2` (2530), little-endian at bytes 2..3 of the FE95 service data |

The device advertises only while no central is connected to it. The Bluetooth icon on its display blinks while it advertises.

## GATT

| Service | Characteristic | Properties | Use |
|---|---|---|---|
| `FE95` | `0x0004` | read | Firmware version, for example `1.1.1_0038` |
| `FE95` | `0x0010` (UPNP) | write, notify | Auth commands and results |
| `FE95` | `0x0019` (AVDTP) | write without response, notify | Auth data, sent as parcels |
| `FEB7` | `FEA7` | write without response | Encrypted app messages to the device (unused by Zamer) |
| `FEB7` | `FEA8` | notify | Encrypted messages from the device, including measurements |

Enable notifications on `0x0010`, `0x0019` and `FEA8` before you start the auth exchange.

## Parcels on AVDTP

Data longer than one write travels as a parcel.

1. The sender writes a 6-byte header `00 00 <type u16> <frame count u16 LE>`.
2. The receiver answers `00 00 01 01` (ready).
3. The sender writes frames `[index u16 LE, starting at 1][up to 18 bytes]`.
4. The receiver answers `00 00 01 00` (received).

## Registration

Registration pairs the phone with the device and produces a 12-byte token. Store the token. A new registration from any app replaces the previous token, so only one app can be paired at a time. The device does not need a reset before you register again.

1. Write `a2000000` to UPNP. The device sends a parcel with device info. Bytes 4..23 of the payload are the 20-byte device ID (DID).
2. Write `15000000` to UPNP. Send your P-256 public key (64 bytes, X then Y) as a parcel with header `000000030400`.
3. Receive the device's 64-byte public key as a parcel.
4. Compute the ECDH shared secret. Derive 64 bytes with HKDF-SHA256, no salt, info `mible-setup-info`. The token is bytes 0..11. The key `A` is bytes 28..43.
5. Encrypt the DID with AES-CCM (key `A`, nonce `10 11 .. 1b`, associated data `devID`, 4-byte tag). Send it as a parcel with header `000000000200`.
6. Write `13000000` to UPNP. The device answers `11000000` on success.

## Login

Run login on every connection.

1. Write `24000000` to UPNP. Generate 16 random bytes (app random). Send them as a parcel with header `0000000b0100`.
2. Receive the device's 16-byte random. The device sends it either as a parcel with header `0000000d0100` or inline as `0000020d` followed by the 16 bytes. Answer the inline form with `00000300`.
3. Receive a parcel with header `0000000c0200` that holds 32 bytes of device proof.
4. Derive 64 bytes with HKDF-SHA256 from the token, salt = app random + device random, info `mible-login-info`.

   | Bytes | Name |
   |---|---|
   | 0..15 | `dev_key` |
   | 16..31 | `app_key` |
   | 32..35 | `dev_iv` |
   | 36..39 | `app_iv` |

5. Check that the device proof equals HMAC-SHA256(`dev_key`, device random + app random). A mismatch means the device does not know your token, because another app registered after you.
6. Send HMAC-SHA256(`app_key`, app random + device random) as a parcel with header `0000000a0200`.
7. UPNP answers `21000000` on success.

## Session messages

Each `FEA8` notification is one message:

```
[counter u16 LE][AES-CCM ciphertext][4-byte tag]
```

Decrypt with key `dev_key` and the 12-byte nonce `dev_iv + 00 00 00 00 + counter as u32 LE`. There is no associated data.

| Plaintext | Meaning |
|---|---|
| `00 00 05 80 04 00 <mm u16 BE>` | Measurement in millimetres. The device sends it when you press the measure button. |
| `00 00 04 80 02 <id> 00` | Status event. The device sends one about every 50 seconds. |

The first two plaintext bytes have been `00 00` in every message seen so far. Example: the plaintext `00 00 05 80 04 00 05 b7` is 1463 mm, and the display showed 1.463 m.

## Unknowns

- The display's Bluetooth icon keeps blinking after a successful login. The official plugin may send an app message on `FEA7` that stops it.
- Commands for the device settings (measuring reference edge, display units) are not known.

## Credits

The [miauth](https://github.com/dnandha/miauth) project documents the Xiaomi standard auth scheme. Zamer's implementation is written from the description above and contains no code from miauth.
