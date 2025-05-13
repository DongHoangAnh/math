const PROTOCOL = window.location.protocol === 'https:' ? 'wss' : 'ws';
const HOST = window.location.host;
const WS_URL = `${PROTOCOL}://${HOST}/ws/game`;
let webSocket = null;
let playerName = '';
let correctCount = 0;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 3;
let timerInterval = null;
let canvas = null;
let ctx = null;
let drawing = false;

const initCanvas = () => {
    canvas = document.getElementById('canvas');
    if (!canvas) {
        console.error('Canvas element not found');
        document.getElementById('resultMessage').textContent = 'Error: Canvas not found';
        return;
    }
    ctx = canvas.getContext('2d');
    ctx.lineWidth = 10;
    ctx.lineCap = 'round';
    ctx.strokeStyle = 'black';
    clearCanvas();

    canvas.addEventListener('mousedown', startDrawing);
    canvas.addEventListener('mousemove', draw);
    canvas.addEventListener('mouseup', stopDrawing);
    canvas.addEventListener('mouseout', stopDrawing);

    canvas.addEventListener('touchstart', (e) => {
        e.preventDefault();
        startDrawing({ clientX: e.touches[0].clientX, clientY: e.touches[0].clientY });
    });
    canvas.addEventListener('touchmove', (e) => {
        e.preventDefault();
        draw({ clientX: e.touches[0].clientX, clientY: e.touches[0].clientY });
    });
    canvas.addEventListener('touchend', stopDrawing);
};

const startDrawing = (e) => {
    drawing = true;
    ctx.beginPath();
    draw(e);
};

const draw = (e) => {
    if (!drawing) return;
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    ctx.lineTo(x, y);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(x, y);
};

const stopDrawing = () => {
    drawing = false;
    ctx.beginPath();
    recognizeSymbol();
};

const clearCanvas = () => {
    if (!ctx) return;
    ctx.fillStyle = 'white';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    document.getElementById('recognized').textContent = '';
};

const recognizeSymbol = () => {
    if (!ctx) return;
    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const pixels = imageData.data;
    let points = [];

    // Lọc điểm đen (ngưỡng màu đơn giản)
    for (let y = 0; y < canvas.height; y++) {
        for (let x = 0; x < canvas.width; x++) {
            const i = (y * canvas.width + x) * 4;
            const r = pixels[i];
            const g = pixels[i + 1];
            const b = pixels[i + 2];
            if (r < 200 && g < 200 && b < 200) { // Nhận diện màu đen
                points.push({ x, y });
            }
        }
    }

    if (points.length < 5) { // Ngưỡng điểm tối thiểu giảm xuống 5
        document.getElementById('recognized').textContent = 'Please draw a symbol (try again).';
        return;
    }

    // Khử nhiễu: Loại bỏ điểm lẻ tẻ (khoảng cách > 10px từ điểm gần nhất)
    points = points.filter(p => {
        const neighbors = points.filter(q =>
            Math.hypot(p.x - q.x, p.y - q.y) <= 10 && p !== q
        );
        return neighbors.length > 0;
    });

    if (points.length < 3) {
        document.getElementById('recognized').textContent = 'Draw more clearly (try again).';
        return;
    }

    // Tìm bounding box
    let minX = Math.min(...points.map(p => p.x));
    let maxX = Math.max(...points.map(p => p.x));
    let minY = Math.min(...points.map(p => p.y));
    let maxY = Math.max(...points.map(p => p.y));
    const width = maxX - minX;
    const height = maxY - minY;

    // Chuẩn hóa điểm
    const normalizedPoints = points.map(p => ({
        x: width > 0 ? (p.x - minX) / width : 0.5,
        y: height > 0 ? (p.y - minY) / height : 0.5
    }));

    // Tính đặc trưng hình học
    const centerX = normalizedPoints.reduce((sum, p) => sum + p.x, 0) / normalizedPoints.length;
    const centerY = normalizedPoints.reduce((sum, p) => sum + p.y, 0) / normalizedPoints.length;
    const leftPoints = normalizedPoints.filter(p => p.x < centerX).length;
    const rightPoints = normalizedPoints.filter(p => p.x > centerX).length;
    const topPoints = normalizedPoints.filter(p => p.y < centerY).length;
    const bottomPoints = normalizedPoints.filter(p => p.y > centerY).length;

    // Tính góc nghiêng trung bình (dựa trên vector giữa các điểm)
    let angleSum = 0;
    for (let i = 0; i < points.length - 1; i++) {
        const dx = points[i + 1].x - points[i].x;
        const dy = points[i + 1].y - points[i].y;
        const angle = Math.atan2(dy, dx) * 180 / Math.PI;
        angleSum += angle;
    }
    const avgAngle = angleSum / (points.length - 1);

    // Phân loại ký hiệu
    let symbol = null;
    const angleThreshold = 30; // Dung sai góc ±30 độ
    const balanceThreshold = 0.3; // Dung sai tỷ lệ điểm

    if (width > height * 0.5 && Math.abs(topPoints - bottomPoints) / points.length < balanceThreshold) {
        symbol = '='; // Dấu =: phân bố ngang, cân bằng trên/dưới
    } else if (leftPoints > rightPoints * (1 + balanceThreshold) && Math.abs(avgAngle) > 45) {
        symbol = '<'; // Dấu <: bên trái nhiều hơn, góc nghiêng
    } else if (rightPoints > leftPoints * (1 + balanceThreshold) && Math.abs(avgAngle) > 45) {
        symbol = '>'; // Dấu >: bên phải nhiều hơn, góc nghiêng
    }

    if (symbol) {
        document.getElementById('recognized').textContent = `Recognized: ${symbol}`;
    } else {
        document.getElementById('recognized').textContent =
            'Unrecognized symbol. Try drawing <, >, or = again.';
    }
};

const checkServerStatus = async () => {
    try {
        const response = await fetch(`http://${HOST}/`, { method: 'HEAD' });
        return response.ok;
    } catch (error) {
        console.error('Server không phản hồi:', error);
        return false;
    }
};

const connectWebSocket = () => {
    checkServerStatus().then((isServerUp) => {
        if (!isServerUp) {
            document.getElementById('result').classList.remove('hidden');
            document.getElementById('resultMessage').textContent =
                'Server không phản hồi. Vui lòng kiểm tra backend tại http://localhost:8080.';
            document.getElementById('retryConnect').classList.remove('hidden');
            return;
        }

        webSocket = new WebSocket(WS_URL);
        document.getElementById('resultMessage').textContent = 'Connecting to server...';
        document.getElementById('retryConnect').classList.add('hidden');

        webSocket.onopen = () => {
            console.log(`Đã kết nối WebSocket tới ${WS_URL}`);
            reconnectAttempts = 0;
            document.getElementById('resultMessage').textContent = '';
        };

        webSocket.onmessage = (event) => {
            const data = JSON.parse(event.data);
            console.log('Received:', data);

            if (data.type === 'waiting') {
                document.getElementById('join').classList.add('hidden');
                document.getElementById('waiting').classList.remove('hidden');
                document.getElementById('resultMessage').textContent = '';
            } else if (data.type === 'comparison') {
                document.getElementById('waiting').classList.add('hidden');
                document.getElementById('gameplay').classList.remove('hidden');
                document.getElementById('comparison').textContent = data.comparison;
                document.getElementById('correctCount').textContent = `Correct: ${correctCount}/${data.total}`;
                document.getElementById('resultMessage').textContent = '';
                clearCanvas();
                startTimer(data.time);
            } else if (data.type === 'result') {
                if (data.correct) {
                    correctCount++;
                    document.getElementById('correctCount').textContent = `Correct: ${correctCount}/${data.total}`;
                }
                document.getElementById('resultMessage').textContent = data.message;
            } else if (data.type === 'gameover' || data.type === 'disconnected') {
                clearInterval(timerInterval);
                document.getElementById('gameplay').classList.add('hidden');
                document.getElementById('result').classList.remove('hidden');
                document.getElementById('comparison').textContent = '';
                document.getElementById('correctCount').textContent = '';
                document.getElementById('timer').textContent = '';
                document.getElementById('resultMessage').textContent = data.message;
            } else if (data.type === 'error') {
                document.getElementById('resultMessage').textContent = data.message;
                document.getElementById('retryConnect').classList.remove('hidden');
            }
        };

        webSocket.onclose = (event) => {
            console.error('WebSocket closed:', event);
            reconnectAttempts++;
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                console.log(`Thử lại kết nối (${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})...`);
                document.getElementById('resultMessage').textContent = `Retrying connection (${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})...`;
                setTimeout(connectWebSocket, 5000);
            } else {
                document.getElementById('gameplay').classList.add('hidden');
                document.getElementById('result').classList.remove('hidden');
                document.getElementById('resultMessage').textContent =
                    'Connection failed after multiple attempts. Please check the server or retry.';
                document.getElementById('retryConnect').classList.remove('hidden');
            }
        };

        webSocket.onerror = (error) => {
            console.error('Lỗi WebSocket:', error);
        };
    });
};

const startTimer = (seconds) => {
    let timeLeft = seconds;
    document.getElementById('timer').textContent = `Time left: ${timeLeft}s`;
    clearInterval(timerInterval);
    timerInterval = setInterval(() => {
        timeLeft--;
        if (timeLeft >= 0) {
            document.getElementById('timer').textContent = `Time left: ${timeLeft}s`;
        }
        if (timeLeft <= 0) {
            clearInterval(timerInterval);
            document.getElementById('timer').textContent = '';
        }
    }, 1000);
};

const submitAnswer = () => {
    const recognizedText = document.getElementById('recognized').textContent;
    const answer = recognizedText.replace('Recognized: ', '');
    if (['<', '>', '='].includes(answer) && webSocket && webSocket.readyState === WebSocket.OPEN) {
        const message = JSON.stringify({ type: 'answer', answer });
        webSocket.send(message);
        clearCanvas();
    } else {
        document.getElementById('resultMessage').textContent =
            'Please draw a valid symbol (<, >, or =) or reconnect.';
    }
};

const resetGame = () => {
    document.getElementById('result').classList.add('hidden');
    document.getElementById('join').classList.remove('hidden');
    document.getElementById('waiting').classList.add('hidden');
    document.getElementById('gameplay').classList.add('hidden');
    document.getElementById('comparison').textContent = '';
    document.getElementById('correctCount').textContent = '';
    document.getElementById('timer').textContent = '';
    document.getElementById('resultMessage').textContent = '';
    document.getElementById('retryConnect').classList.add('hidden');
    clearInterval(timerInterval);
    if (webSocket && webSocket.readyState === WebSocket.OPEN) {
        webSocket.close();
    }
    reconnectAttempts = 0;
    correctCount = 0;
};

document.getElementById('joinGame').addEventListener('click', () => {
    playerName = document.getElementById('playerName').value.trim() || `Player${Math.floor(Math.random() * 1000)}`;
    correctCount = 0;
    document.getElementById('resultMessage').textContent = '';
    connectWebSocket();
});

document.getElementById('clearCanvas').addEventListener('click', clearCanvas);
document.getElementById('submitAnswer').addEventListener('click', submitAnswer);
document.getElementById('playAgain').addEventListener('click', resetGame);
document.getElementById('retryConnect').addEventListener('click', () => {
    reconnectAttempts = 0;
    connectWebSocket();
});

window.onload = () => {
    initCanvas();
};