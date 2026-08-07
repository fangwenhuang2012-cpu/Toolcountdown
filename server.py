import http.server
import socketserver
import socket
import sys
import os

PORT = 8080

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

class CustomHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # Enable CORS and caching headers
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        super().end_headers()

if __name__ == "__main__":
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')

    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    local_ip = get_local_ip()
    
    print("=" * 60)
    print(" DIGITAL COUNTDOWN TIMER SERVER - ANDROID BOX")
    print("=" * 60)
    print(f" -> Local access on PC:        http://localhost:{PORT}")
    print(f" -> Android Box / Network IP:   http://{local_ip}:{PORT}")
    print("=" * 60)
    print(" Mo trinh duyet tren Android Box va nhap dia chi IP o tren de dung!")
    print(" Nhan Ctrl+C de dung server.\n")

    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", PORT), CustomHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nĐã dừng server.")
            sys.exit(0)
