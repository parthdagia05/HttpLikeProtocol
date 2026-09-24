# Calculator that stays on the line

An HTTP/1.1 calculator server in Java, written on a bare socket with no framework.
One TCP connection can carry any number of requests.

## Build and run

```sh
./build.sh
./run.sh            # port 8080, idle timeout 60s
./run.sh -p 9000 -t 30
```

## Routes

| Request | Status | Body |
|---|---|---|
| `GET /add?a=2&b=3` | 200 | `5` |
| `GET /sub?a=10&b=4` | 200 | `6` |
| `GET /mul?a=6&b=7` | 200 | `42` |
| `GET /div?a=9&b=3` | 200 | `3` |
| `GET /div?a=1&b=0` | 400 | division by zero |
| `GET /add?a=x&b=3` | 400 | not a number |
| `GET /pow?a=2&b=8` | 404 | no such operation |
| `POST /add` | 405 | use GET |
| `GET /add` with no Host | 400 | missing Host header |

## Where one request ends and the next begins

The server reads the headers up to the first empty line. Then it reads exactly
`Content-Length` bytes of body, or the chunks of a chunked body, and nothing more.
The next byte stays in the buffer as the start of the next request.

If a request cannot be framed, the server answers 400 and closes the connection,
because it cannot know where the next request starts. Examples are a broken request
line, a bad `Content-Length`, or both `Content-Length` and `Transfer-Encoding`.
For any other error, the connection stays open.

## Stretch goals

* **Connection: close** is honoured. HTTP/1.0 clients are closed unless they send `Connection: keep-alive`.
* **Idle timeout** defaults to 60 seconds and is advertised in a `Keep-Alive` header.
  60 seconds is long enough for someone typing requests by hand in a Python shell.
  It is still short enough that dead clients do not hold connections open for long.
* **Chunked request bodies** are decoded, so they are framed correctly too.
* **Pipelining** works: send all six requests at once and the answers come back in order.

## Test

```sh
python3 test/test_server.py
```

This starts the server and runs 21 checks over raw sockets. The first block is the marking script.
