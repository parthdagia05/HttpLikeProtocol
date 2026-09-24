#!/usr/bin/env python3
"""End-to-end tests. Starts the server, then talks to it over raw sockets only.

The first test is the marking script from the assignment: one socket, every request.
"""
import os
import socket
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = 18080
IDLE = 2
passed = failed = 0


def check(name, cond):
    global passed, failed
    if cond:
        passed += 1
        print("PASS ", name)
    else:
        failed += 1
        print("FAIL ", name)


def connect():
    s = socket.create_connection(("localhost", PORT))
    s.settimeout(5)
    return s, s.makefile("rb")


def get(path, host=True, extra=""):
    h = "Host: localhost\r\n" if host else ""
    return f"GET {path} HTTP/1.1\r\n{h}{extra}\r\n".encode()


def read_response(f):
    """Reads exactly one response: status line, headers, then Content-Length bytes."""
    line = f.readline()
    if not line:
        return None
    status = int(line.split()[1])
    headers = {}
    while True:
        line = f.readline().decode().rstrip("\r\n")
        if not line:
            break
        k, v = line.split(":", 1)
        headers[k.strip().lower()] = v.strip()
    body = f.read(int(headers["content-length"]))
    return status, headers, body.decode()


def closed_by_server(s):
    try:
        return s.recv(1) == b""
    except (ConnectionResetError, socket.timeout):
        return False


def main():
    subprocess.run([os.path.join(ROOT, "build.sh")], check=True, stdout=subprocess.DEVNULL)
    server = subprocess.Popen([os.path.join(ROOT, "run.sh"), "-p", str(PORT), "-t", str(IDLE)],
                              stderr=subprocess.DEVNULL)
    try:
        for _ in range(50):
            try:
                socket.create_connection(("localhost", PORT)).close()
                break
            except OSError:
                time.sleep(0.1)
        run_tests()
    finally:
        server.terminate()
    print(f"{passed} passed, {failed} failed")
    sys.exit(1 if failed else 0)


def run_tests():
    # The marking script: one TCP handshake, six responses, socket still open.
    s, f = connect()
    marking = [
        (get("/add?a=2&b=3"), 200, "5"),
        (get("/sub?a=10&b=4"), 200, "6"),
        (get("/mul?a=6&b=7"), 200, "42"),
        (get("/div?a=1&b=0"), 400, None),
        (get("/pow?a=2&b=8"), 404, None),
        (b"POST /add HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n", 405, None),
    ]
    for req, want_status, want_body in marking:
        s.sendall(req)
        status, _, body = read_response(f)
        name = req.split(b"\r\n")[0].decode()
        check(f"{name} -> {want_status}", status == want_status and (want_body is None or body == want_body))
    s.sendall(get("/div?a=9&b=3"))
    check("socket still open after six responses", read_response(f)[1:] and True)
    s.close()

    # The rest of the feature table, also on one socket.
    s, f = connect()
    for req, want in [(get("/div?a=9&b=3"), (200, "3")),
                      (get("/add?a=x&b=3"), (400, None)),
                      (get("/add", host=False), (400, None)),
                      (get("/add?a=2"), (400, None)),
                      (get("/div?a=7&b=2"), (200, "3.5")),
                      (get("/sub?a=-1.5&b=2"), (200, "-3.5"))]:
        s.sendall(req)
        status, _, body = read_response(f)
        check(f"{req.split(b' ')[1].decode()} -> {want[0]}", status == want[0] and (want[1] is None or body == want[1]))
    s.close()

    # The hard part: a request body is exactly Content-Length bytes, and byte n+1 is the next request.
    s, f = connect()
    s.sendall(b"POST /add HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\n\r\nhello world" + get("/add?a=1&b=1"))
    r1, r2 = read_response(f), read_response(f)
    check("body consumed exactly, next request on same socket works", r1[0] == 405 and r2[0] == 200 and r2[2] == "2")
    s.close()

    # Chunked request body is framed by its chunks, not by EOF.
    s, f = connect()
    s.sendall(b"POST /mul HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
              b"5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n" + get("/mul?a=3&b=3"))
    r1, r2 = read_response(f), read_response(f)
    check("chunked body consumed, next request works", r1[0] == 405 and r2 == (200, r2[1], "9"))
    s.close()

    # Pipelining: all six at once, answers come back in order.
    s, f = connect()
    s.sendall(b"".join(req for req, _, _ in marking))
    got = [read_response(f)[0] for _ in marking]
    check("pipelined six requests answered in order", got == [200, 200, 200, 400, 404, 405])
    s.close()

    # Connection: close is honoured.
    s, f = connect()
    s.sendall(get("/add?a=1&b=2", extra="Connection: close\r\n"))
    status, headers, body = read_response(f)
    check("Connection: close answers then closes", body == "3" and headers["connection"] == "close" and closed_by_server(s))
    s.close()

    # HTTP/1.0 is not persistent unless it asks.
    s, f = connect()
    s.sendall(b"GET /add?a=1&b=2 HTTP/1.0\r\n\r\n")
    check("HTTP/1.0 without keep-alive closes", read_response(f)[2] == "3" and closed_by_server(s))
    s.close()

    # Unframeable request: answer 400 and close, since the next byte can't be trusted.
    s, f = connect()
    s.sendall(b"POST /add HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n")
    check("Content-Length plus chunked -> 400 and close", read_response(f)[0] == 400 and closed_by_server(s))
    s.close()

    s, f = connect()
    s.sendall(b"hello there\r\n\r\n")
    check("garbage request line -> 400 and close", read_response(f)[0] == 400 and closed_by_server(s))
    s.close()

    # Idle timeout: a silent client is dropped after IDLE seconds, not before.
    s, f = connect()
    s.sendall(get("/add?a=1&b=1"))
    read_response(f)
    time.sleep(IDLE - 1)
    s.sendall(get("/add?a=2&b=2"))
    still_open = read_response(f)[2] == "4"
    s.settimeout(IDLE + 3)
    check("idle timeout closes a silent socket, not an active one", still_open and closed_by_server(s))
    s.close()


if __name__ == "__main__":
    main()
