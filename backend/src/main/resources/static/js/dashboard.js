let chart = null;
let allDecisions = []; // Cache decisions for client-side search/filtering

document.addEventListener('DOMContentLoaded', () => {
    initChart();
    pollStatus();
    // Poll status every 4 seconds to keep the dashboard responsive and active
    setInterval(pollStatus, 4000);
});

function initChart() {
    const ctx = document.getElementById('telemetryChart').getContext('2d');
    chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                {
                    label: 'Avg CPU Load (%)',
                    borderColor: '#8b5cf6',
                    backgroundColor: 'rgba(139, 92, 246, 0.1)',
                    borderWidth: 3,
                    fill: true,
                    tension: 0.4,
                    data: [],
                    yAxisID: 'y'
                },
                {
                    label: 'Active Replicas',
                    borderColor: '#3b82f6',
                    backgroundColor: 'transparent',
                    borderWidth: 2,
                    borderDash: [5, 5],
                    data: [],
                    yAxisID: 'y1'
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                x: {
                    grid: {
                        color: 'rgba(255, 255, 255, 0.05)'
                    },
                    ticks: {
                        color: '#9ca3af',
                        font: {
                            family: 'Inter'
                        }
                    }
                },
                y: {
                    position: 'left',
                    min: 0,
                    max: 100,
                    grid: {
                        color: 'rgba(255, 255, 255, 0.05)'
                    },
                    ticks: {
                        color: '#9ca3af',
                        callback: function(value) { return value + '%'; }
                    }
                },
                y1: {
                    position: 'right',
                    min: 0,
                    max: 10,
                    grid: {
                        drawOnChartArea: false
                    },
                    ticks: {
                        color: '#9ca3af',
                        stepSize: 1
                    }
                }
            },
            plugins: {
                legend: {
                    labels: {
                        color: '#f3f4f6',
                        font: {
                            family: 'Outfit',
                            weight: '600'
                        }
                    }
                }
            }
        }
    });
}

function pollStatus() {
    fetch('/dashboard/api/status')
        .then(response => response.json())
        .then(data => {
            updateMetrics(data);
            updateNodesTable(data.nodesList);
            updateReplicaLoads(data.replicaLoads); // Render individual replica loads
            allDecisions = data.decisions || [];
            filterLogs(); // Filter and render decisions log
            updateChart(data.snapshots);
            updateSystemStatusDot(data.decisions);
        })
        .catch(err => console.error("Error polling dashboard status: ", err));
}

function updateMetrics(data) {
    document.getElementById('stat-replicas').innerText = data.activeReplicas;
    document.getElementById('stat-nodes').innerText = data.activeNodes;

    // Read CPU from latest snapshot if present
    let cpuVal = 0.0;
    if (data.snapshots && data.snapshots.length > 0) {
        cpuVal = data.snapshots[data.snapshots.length - 1].avgCpu;
    }
    
    document.getElementById('stat-cpu').innerText = cpuVal.toFixed(1);
    document.getElementById('cpu-progress').style.width = cpuVal + '%';

    // Change progress bar color based on load
    const progressBar = document.getElementById('cpu-progress');
    if (cpuVal > 80.0) {
        progressBar.style.background = 'linear-gradient(90deg, #ef4444 0%, #f97316 100%)';
    } else if (cpuVal < 35.0) {
        progressBar.style.background = 'linear-gradient(90deg, #3b82f6 0%, #10b981 100%)';
    } else {
        progressBar.style.background = 'linear-gradient(90deg, #3b82f6 0%, #8b5cf6 100%)';
    }
}

function updateSystemStatusDot(decisions) {
    const dot = document.getElementById('system-status-dot');
    const text = document.getElementById('system-status-text');

    if (!decisions || decisions.length === 0) {
        dot.className = 'status-dot pulse-green';
        text.innerText = 'SYSTEM ACTIVE';
        return;
    }

    const last = decisions[decisions.length - 1];
    
    // Check if the last scale operation was very recent (within 3 minutes)
    const lastTime = new Date(last.timestamp).getTime();
    const diffMin = (Date.now() - lastTime) / 1000 / 60;

    if (diffMin < 3.0 && last.policyPassed) {
        if (last.action === 'SCALE_UP') {
            dot.className = 'status-dot';
            dot.style.backgroundColor = '#10b981';
            text.innerText = 'SCALING UP...';
        } else if (last.action === 'SCALE_DOWN') {
            dot.className = 'status-dot';
            dot.style.backgroundColor = '#ef4444';
            text.innerText = 'SCALING DOWN...';
        }
    } else if (diffMin < 3.0 && !last.policyPassed) {
        dot.className = 'status-dot';
        dot.style.backgroundColor = '#3b82f6';
        text.innerText = 'COOLDOWN LOCK ACTIVE';
    } else {
        dot.className = 'status-dot pulse-green';
        text.innerText = 'SYSTEM ACTIVE';
    }
}

function updateNodesTable(nodes) {
    const tbody = document.getElementById('nodes-table-body');
    if (!nodes || nodes.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="empty-row">No active nodes registered.</td></tr>';
        return;
    }

    let html = '';
    nodes.forEach(node => {
        let badgeClass = 'badge-active';
        if (node.status === 'PENDING') badgeClass = 'badge-pending';
        else if (node.status === 'DRAINING') badgeClass = 'badge-draining';
        else if (node.status === 'TERMINATED') badgeClass = 'badge-terminated';

        const heartbeatTime = new Date(node.lastHeartbeat).toLocaleTimeString();

        html += `<tr>
            <td><strong>${node.hostname}</strong></td>
            <td><code>${node.ipAddress}</code></td>
            <td><span class="status-badge ${badgeClass}">${node.status}</span></td>
            <td>${heartbeatTime}</td>
        </tr>`;
    });
    tbody.innerHTML = html;
}

function filterLogs() {
    const searchQuery = document.getElementById('log-search-input').value.toLowerCase().trim();
    const timeFilter = document.getElementById('log-time-filter').value;
    const now = Date.now();

    const filtered = allDecisions.filter(d => {
        // 1. Apply time filter (default 24h)
        const logTime = new Date(d.timestamp).getTime();
        const diffHours = (now - logTime) / (1000 * 60 * 60);

        if (timeFilter === '24h' && diffHours > 24) return false;
        if (timeFilter === '7d' && diffHours > 24 * 7) return false;

        // 2. Apply text search query filter
        if (searchQuery) {
            const actionMatch = d.action.toLowerCase().includes(searchQuery);
            const reasonMatch = d.decisionReason.toLowerCase().includes(searchQuery);
            const keyMatch = d.idempotencyKey.toLowerCase().includes(searchQuery);
            return actionMatch || reasonMatch || keyMatch;
        }

        return true;
    });

    renderDecisionsTable(filtered);
}

function renderDecisionsTable(decisions) {
    const tbody = document.getElementById('decisions-table-body');
    if (!decisions || decisions.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="empty-row">No scaling events matches the active filters.</td></tr>';
        return;
    }

    let html = '';
    const sorted = [...decisions].reverse(); // Show newest scaling decisions first
    sorted.forEach(d => {
        const dTime = new Date(d.timestamp).toLocaleString();
        const actionClass = d.action === 'SCALE_UP' ? 'action-up' : 'action-down';
        const passText = d.policyPassed ? 'Approved' : 'Policy Blocked';
        
        html += `<tr>
            <td>${dTime}</td>
            <td><span class="badge-action ${actionClass}">${d.action}</span></td>
            <td>${d.decisionReason} <span style="font-size:0.75rem; opacity:0.6;">(${passText})</span></td>
            <td><code>${d.idempotencyKey.substring(0, 8)}...</code></td>
        </tr>`;
    });
    tbody.innerHTML = html;
}

function updateChart(snapshots) {
    if (!snapshots || snapshots.length === 0) return;

    const labels = [];
    const cpuData = [];
    const replicaData = [];

    snapshots.forEach(s => {
        const time = new Date(s.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        labels.push(time);
        cpuData.push(s.avgCpu.toFixed(1));
        replicaData.push(s.activeReplicas);
    });

    chart.data.labels = labels;
    chart.data.datasets[0].data = cpuData;
    chart.data.datasets[1].data = replicaData;
    chart.update('none'); // Update without full transition to avoid visual glitch
}

function triggerLoadSpike(seconds) {
    logAction("Triggering " + seconds + "s traffic spike load simulation on target workload...");
    
    fetch('/dashboard/api/simulate-spike?seconds=' + seconds, {
        method: 'POST'
    })
    .then(res => res.json())
    .then(data => {
        if (data.status === 'SUCCESS') {
            showToast("Load spike simulated successfully!", "green");
        } else {
            showToast("Simulation failed: " + data.error, "red");
        }
    })
    .catch(err => {
        showToast("Network error triggering simulation", "red");
        console.error(err);
    });
}

function logAction(msg) {
    console.log("[ScaleForge Client] " + msg);
}

function showToast(message, color) {
    // Basic browser log as backup
    console.log("%c[ScaleForge Toast] " + message, "color: " + color + "; font-weight: bold;");
}

function updateReplicaLoads(replicaLoads) {
    const listContainer = document.getElementById('replica-loads-list');
    if (!replicaLoads || replicaLoads.length === 0) {
        listContainer.innerHTML = '<div class="empty-row">No active container tasks found.</div>';
        return;
    }

    let html = '';
    replicaLoads.forEach(replica => {
        const cpu = replica.cpuLoad.toFixed(1);
        
        // Dynamic progress bar color based on individual replica load
        let barBackground = 'var(--gradient-main)';
        if (replica.cpuLoad > 80.0) {
            barBackground = 'linear-gradient(90deg, #ef4444 0%, #f97316 100%)';
        } else if (replica.cpuLoad < 35.0) {
            barBackground = 'linear-gradient(90deg, #3b82f6 0%, #10b981 100%)';
        }

        html += `<div class="replica-load-item">
            <div class="replica-load-header">
                <div>
                    <span class="replica-load-name">Task: <strong>${replica.id.substring(0, 10)}...</strong></span>
                    <div class="replica-load-node">Hosted on: <strong>${replica.node}</strong></div>
                </div>
                <div class="replica-load-value" style="color: ${replica.cpuLoad > 80 ? 'var(--accent-red)' : 'var(--text-primary)'}">${cpu}%</div>
            </div>
            <div class="replica-load-progress-container">
                <div class="replica-load-progress" style="width: ${cpu}%; background: ${barBackground};"></div>
            </div>
        </div>`;
    });
    listContainer.innerHTML = html;
}
