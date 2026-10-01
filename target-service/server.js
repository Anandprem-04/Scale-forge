const express = require('express');
const app = express();
const PORT = process.env.PORT || 8081;

let isBurning = false;

app.get('/health', (req, res) => {
    res.json({ status: 'UP', timestamp: new Date().toISOString() });
});

app.get('/metrics', (req, res) => {
    const memory = process.memoryUsage();
    res.json({
        cpuPercent: isBurning ? 92.5 : 4.8,
        memoryUsageMb: Math.round(memory.heapUsed / 1024 / 1024),
        timestamp: new Date().toISOString()
    });
});

app.post('/load', (req, res) => {
    const seconds = parseInt(req.query.seconds) || 15;
    if (isBurning) {
        return res.status(409).json({ error: 'CPU load simulation already active.' });
    }
    
    isBurning = true;
    console.log(`Starting CPU burn for ${seconds} seconds...`);
    
    const endTime = Date.now() + (seconds * 1000);
    
    // Asynchronously burn CPU in chunks so the event loop is not completely locked
    const burn = () => {
        if (Date.now() < endTime) {
            const blockEndTime = Date.now() + 80; // burn for 80ms
            while (Date.now() < blockEndTime) {
                Math.random() * Math.random();
            }
            setTimeout(burn, 20); // yield for 20ms
        } else {
            isBurning = false;
            console.log('CPU load simulation finished.');
        }
    };
    
    burn();
    res.json({ message: `Simulated load spike started for ${seconds} seconds.` });
});

app.listen(PORT, () => {
    console.log(`Target service running on port ${PORT}`);
});
