#!/usr/bin/env python3
"""Optional interop check: pair with the receiver using pyatv's HomeKit client, the one pyatv uses with Apple TVs.

Needs `pip install pyatv` and a compiled InteropServer (tools/test.py --interop runs it). Exits non-zero on failure.
"""
import plistlib
import socket
import sys

from pyatv.auth import hap_tlv8
from pyatv.auth.hap_session import HAPSession
from pyatv.auth.hap_srp import SRPAuthHandler, hkdf_expand

port = int(sys.argv[1])
sock = socket.create_connection(("127.0.0.1", port), timeout=10)
session = HAPSession()
buffer = b""
cseq = 0


def request(method, path, body=b"", ctype=None):
    global buffer, cseq
    cseq += 1
    head = f"{method} {path} RTSP/1.0\r\nCSeq: {cseq}\r\nUser-Agent: AirPlay/320.20\r\nX-Apple-HKP: 4\r\n"
    if ctype:
        head += f"Content-Type: {ctype}\r\n"
    head += f"Content-Length: {len(body)}\r\n\r\n"
    sock.sendall(session.encrypt(head.encode() + body))
    while True:
        plain = buffer
        if b"\r\n\r\n" in plain:
            header, rest = plain.split(b"\r\n\r\n", 1)
            length = 0
            for line in header.split(b"\r\n")[1:]:
                k, _, v = line.partition(b":")
                if k.strip().lower() == b"content-length":
                    length = int(v)
            if len(rest) >= length:
                buffer = rest[length:]
                status = int(header.split(b" ")[1])
                return status, rest[:length]
        data = sock.recv(65536)
        if not data:
            raise SystemExit("connection closed")
        buffer += session.decrypt(data)


srp = SRPAuthHandler()
srp.initialize()
status, body = request("POST", "/pair-setup", hap_tlv8.write_tlv({
    hap_tlv8.TlvValue.Method: b"\x00", hap_tlv8.TlvValue.SeqNo: b"\x01",
    hap_tlv8.TlvValue.Flags: bytes([hap_tlv8.Flags.TransientPairing.value])}), "application/octet-stream")
m2 = hap_tlv8.read_tlv(body)
srp.step1(3939)
pub, proof = srp.step2(m2[hap_tlv8.TlvValue.PublicKey], m2[hap_tlv8.TlvValue.Salt])
status, body = request("POST", "/pair-setup", hap_tlv8.write_tlv({
    hap_tlv8.TlvValue.SeqNo: b"\x03", hap_tlv8.TlvValue.PublicKey: pub, hap_tlv8.TlvValue.Proof: proof}),
    "application/octet-stream")
m4 = hap_tlv8.read_tlv(body)
assert hap_tlv8.TlvValue.Error not in m4, f"server rejected pyatv's SRP proof: {m4}"
assert srp._session.verify_proof(m4[hap_tlv8.TlvValue.Proof].hex().encode()), "server proof does not verify in srptools"
print("pyatv transient pair-setup: OK (SRP agreed with srptools)")

shared = bytes.fromhex(srp.shared_key.decode() if isinstance(srp.shared_key, bytes) else srp.shared_key)
session.enable(hkdf_expand("Control-Salt", "Control-Write-Encryption-Key", shared),
               hkdf_expand("Control-Salt", "Control-Read-Encryption-Key", shared))
status, body = request("GET", "/info")
info = plistlib.loads(body)
assert status == 200 and info["name"] == "Interop Kiosk", info
print("encrypted GET /info: OK ->", info["name"], hex(info["features"]))
status, body = request("SETUP", "rtsp://127.0.0.1/1", plistlib.dumps(
    {"timingProtocol": "PTP", "name": "pyatv", "timingPeerInfo": {"Addresses": ["127.0.0.1"], "ID": "x"}},
    fmt=plistlib.FMT_BINARY), "application/x-apple-binary-plist")
reply = plistlib.loads(body)
assert status == 200 and reply["eventPort"] > 0, reply
print("encrypted SETUP (PTP session): OK ->", reply)
status, body = request("SETUP", "rtsp://127.0.0.1/1", plistlib.dumps(
    {"streams": [{"type": 103, "ct": 4, "spf": 1024, "audioFormat": 0x400000, "shk": bytes(32)}]},
    fmt=plistlib.FMT_BINARY), "application/x-apple-binary-plist")
reply = plistlib.loads(body)
assert status == 200 and reply["streams"][0]["dataPort"] > 0, reply
print("encrypted SETUP (buffered AAC stream): OK ->", reply)
print("interop passed")
