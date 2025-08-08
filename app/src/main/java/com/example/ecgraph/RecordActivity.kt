package com.example.ecgraph

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class RecordActivity : AppCompatActivity() {
    private lateinit var ecgChart: LineChart
    private val handler = Handler(Looper.getMainLooper())
    private var ecgData = mutableListOf<Entry>()
    private var currentIndex = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ecg_record)

        ecgChart = findViewById(R.id.ecgChart)
        setupChart()

        val btnStart = findViewById<Button>(R.id.btnStart)
        btnStart.setOnClickListener {
            loadECGData()
            //startRealTimeUpdate()
        }

        val btnStop = findViewById<Button>(R.id.btnStop)
        btnStop.setOnClickListener {
            val intent = Intent(this, AnalysisActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    fun setupChart() {
        ecgChart.apply {
            setTouchEnabled(false)
            setScaleEnabled(false)
            setDrawGridBackground(true)
            setGridBackgroundColor(Color.parseColor("#4C9A84"))
            description.isEnabled = false
            legend.isEnabled = false

            // Trục X
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                setDrawAxisLine(true)
                setDrawLabels(true)
                axisLineColor = Color.BLACK
                textColor = Color.BLACK
                gridColor = Color.BLACK
                granularity = 0.1f
                setLabelCount(10, true)
            }

            // Trục Y trái
            axisLeft.apply {
                setDrawGridLines(true)
                setDrawAxisLine(true)
                setDrawLabels(true)
                axisLineColor = Color.BLACK
                textColor = Color.BLACK
                gridColor = Color.BLACK
                granularity = 0.1f
                setLabelCount(8, true)
            }

            // Ẩn trục Y phải
            axisRight.isEnabled = false
        }
    }


    private fun startRealTimeUpdate() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (currentIndex >= ecgData.size) return // Dừng nếu hết dữ liệu

                // Lấy dữ liệu từ ecgData đã đọc từ file
                val entry = ecgData[currentIndex]

                // Thêm vào biểu đồ cuộn
                val visibleData = ecgChart.data?.getDataSetByIndex(0) as? LineDataSet
                    ?: LineDataSet(mutableListOf(), "ECG").apply {
                        setDrawCircles(false)
                        setDrawValues(false)
                        lineWidth = 2f
                        color = Color.RED
                    }

                visibleData.addEntry(entry)

                // Giữ lại tối đa 200 điểm gần nhất
                if (visibleData.entryCount > 200) {
                    visibleData.removeFirst()
                }

                ecgChart.data = LineData(visibleData)
                ecgChart.setVisibleXRangeMaximum(200f)
                ecgChart.moveViewToX(entry.x - 200)
                ecgChart.invalidate()

                currentIndex++
                handler.postDelayed(this, 50) // Lặp lại sau 50ms ~ 20fps
            }
        }, 50)
    }

    private fun loadECGData() {
        try {
            val inputStream = assets.open("ecg_synthetic_data.csv")
            val content = inputStream.bufferedReader().use { it.readText() }
            Log.d("ASSETS", "Nội dung file:\n$content")

            val lines = content.lines().drop(1)
            ecgData.clear()

            for ((index, line) in lines.withIndex()) {
                if (line.isBlank()) continue

                val parts = line.split(",")
                if (parts.size < 2) {
                    Log.w("CSV_PARSE", "Thiếu dữ liệu tại dòng $index: $line")
                    continue
                }

                val t = parts[0].toFloatOrNull()
                val v = parts[1].toFloatOrNull()

                if (t != null && v != null) {
                    ecgData.add(Entry(t, v))
                } else {
                    Log.w("CSV_PARSE", "Lỗi chuyển đổi số dòng $index: $line")
                }
            }

            val dataSet = LineDataSet(ecgData, "ECG Signal").apply {
                color = Color.RED
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 1.5f
            }

            ecgChart.data = LineData(dataSet)
            ecgChart.invalidate()
        } catch (e: Exception) {
            Log.e("ASSETS_ERROR", "Lỗi khi đọc file: ${e.message}")
            e.printStackTrace()
        }
    }
}
