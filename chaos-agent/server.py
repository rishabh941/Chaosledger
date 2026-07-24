#!/usr/bin/env python3
"""
Chaos Agent for ChaosLedger.

Lightweight HTTP server that pauses/unpauses Docker containers and resets
Toxiproxy. Talks to Docker Engine via Unix socket (/var/run/docker.sock).

Endpoints:
  POST /pause/{nodeId}     — pause a ledger node container
  POST /unpause/{nodeId}   — unpause a ledger node container
  POST /heal-all           — unpause all nodes + reset Toxiproxy
  GET  /health             — health check
"""
import http.client
import http.server
import json
import os
import socket
import urllib.parse

DOCKER_SOCK = '/var/run/docker.sock'
TOXIPROXY_HOST = os.environ.get('TOXIPROXY_HOST', 'toxiproxy')
TOXIPROXY_PORT = int(os.environ.get('TOXIPROXY_PORT', '8474'))


class DockerSocket:

    @staticmethod
    def _request(method, path):
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.connect(DOCKER_SOCK)
        conn = http.client.HTTPConnection('localhost')
        conn.sock = sock
        conn.request(method, path)
        resp = conn.getresponse()
        body = resp.read().decode()
        status = resp.status
        conn.close()
        return status, body

    @classmethod
    def find_container(cls, service_name):
        filters = json.dumps({
            "label": [f"com.docker.compose.service={service_name}"],
            "status": ["running", "paused"],
        })
        status, body = cls._request(
            'GET',
            f'/v1.46/containers/json?all=true&filters={urllib.parse.quote(filters)}',
        )
        if status != 200:
            return None, None
        containers = json.loads(body)
        if not containers:
            return None, None
        c = containers[0]
        return c['Id'], c.get('State', '')

    @classmethod
    def pause(cls, container_id):
        status, _ = cls._request('POST', f'/v1.46/containers/{container_id}/pause')
        return status

    @classmethod
    def unpause(cls, container_id):
        status, _ = cls._request('POST', f'/v1.46/containers/{container_id}/unpause')
        return status


def reset_toxiproxy():
    try:
        conn = http.client.HTTPConnection(TOXIPROXY_HOST, TOXIPROXY_PORT, timeout=5)
        conn.request('POST', '/reset')
        resp = conn.getresponse()
        resp.read()
        conn.close()
        return resp.status in (200, 204)
    except Exception as e:
        print(f"[chaos-agent] Toxiproxy reset failed: {e}")
        return False


class Handler(http.server.BaseHTTPRequestHandler):

    def do_POST(self):
        path = self.path.strip('/')
        parts = path.split('/')

        if len(parts) == 2 and parts[0] in ('pause', 'unpause'):
            self._handle_lifecycle(parts[0], parts[1])
        elif path == 'heal-all':
            self._handle_heal_all()
        else:
            self._json(400, {"error": "POST /pause/{nodeId}, /unpause/{nodeId}, or /heal-all"})

    def _handle_lifecycle(self, action, node_id):
        service = f'ledger-{node_id}'
        cid, state = DockerSocket.find_container(service)
        if not cid:
            self._json(404, {"error": f"No container for service '{service}'"})
            return

        if action == 'pause':
            status = DockerSocket.pause(cid)
            if status in (200, 204):
                self._json(200, {"action": "paused", "node": int(node_id)})
            elif status == 409:
                self._json(200, {"action": "already_paused", "node": int(node_id)})
            else:
                self._json(500, {"error": f"Docker pause returned {status}"})
        else:
            status = DockerSocket.unpause(cid)
            if status in (200, 204):
                self._json(200, {"action": "unpaused", "node": int(node_id)})
            elif status == 409:
                self._json(200, {"action": "already_running", "node": int(node_id)})
            else:
                self._json(500, {"error": f"Docker unpause returned {status}"})

    def _handle_heal_all(self):
        results = []
        for nid in (1, 2, 3):
            cid, state = DockerSocket.find_container(f'ledger-{nid}')
            if cid:
                status = DockerSocket.unpause(cid)
                results.append({
                    "node": nid,
                    "result": "unpaused" if status in (200, 204)
                             else "already_running" if status == 409
                             else f"error({status})",
                })
        toxi_ok = reset_toxiproxy()
        self._json(200, {
            "action": "heal_all",
            "containers": results,
            "toxiproxy_reset": toxi_ok,
        })

    def do_GET(self):
        if self.path == '/health':
            self._json(200, {"status": "ok", "docker": os.path.exists(DOCKER_SOCK)})
        else:
            self._json(404, {"error": "Not found"})

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors_headers()
        self.end_headers()

    def _json(self, code, body):
        payload = json.dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self._cors_headers()
        self.end_headers()
        self.wfile.write(payload)

    def _cors_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')

    def log_message(self, fmt, *args):
        print(f"[chaos-agent] {fmt % args}")


if __name__ == '__main__':
    port = int(os.environ.get('PORT', '8475'))
    server = http.server.HTTPServer(('0.0.0.0', port), Handler)
    print(f"[chaos-agent] listening on :{port}")
    print(f"[chaos-agent] docker socket: {DOCKER_SOCK}")
    print(f"[chaos-agent] toxiproxy: {TOXIPROXY_HOST}:{TOXIPROXY_PORT}")
    server.serve_forever()
