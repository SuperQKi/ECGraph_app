package com.example.ecgraph

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class MainActivity : AppCompatActivity() {

    private lateinit var ecgChart: LineChart
    private val handler = Handler(Looper.getMainLooper())
    private val ecgData = mutableListOf<Entry>()
    private var time = 0f

    //====================main=================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.record_ecg) // to call layout record_ecg.xml
        ecgChart = findViewById(R.id.ecgChart) // trỏ đến ecgChart trong layout
        //=============noi
        setupChart()
        loadECGData()
        startRealTimeUpdate()
    }
    //======================ECG graph================================
    //======================setup chart==============================
    private fun setupChart() {
        ecgChart.apply {
            setTouchEnabled(false)
            setScaleEnabled(false)
            setDrawGridBackground(true)
            setGridBackgroundColor(Color.WHITE)
            description.isEnabled = false
            legend.isEnabled = false

            // Trục X
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                setDrawAxisLine(true)
                setDrawLabels(false)

                granularity = 1f
                labelCount = 30  // chia 30 ô theo chiều ngang
            }

            // Trục Y trái
            axisLeft.apply {
                setDrawGridLines(true)
                setDrawAxisLine(true)
                setDrawLabels(false)

                granularity = 1f
                labelCount = 10  // chia 10 ô theo chiều dọc
            }

            // Ẩn trục Y phải
            axisRight.isEnabled = false
        }
    }
    //======================Hiển thị biểu đồ theo thời gian thực===================
    private fun startRealTimeUpdate() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // Tạo điểm mới giống sóng ECG
                val y = (Math.sin(time * 0.2) * 100 + Math.random() * 10).toFloat()
                ecgData.add(Entry(time, y))

                // Chỉ giữ 200 điểm gần nhất (để nhìn như cuộn sóng)
                if (ecgData.size > 200) ecgData.removeAt(0)

                val dataSet = LineDataSet(ecgData, "ECG").apply {
                    setDrawCircles(false)
                    setDrawValues(false)
                    lineWidth = 2f
                    color = Color.RED
                }

                ecgChart.data = LineData(dataSet)
                ecgChart.setVisibleXRangeMaximum(200f) // Chỉ hiển thị 200 điểm gần nhất
                ecgChart.moveViewToX(time - 200)       // Di chuyển biểu đồ theo thời gian
                ecgChart.invalidate()

                time += 1f
                handler.postDelayed(this, 50) // Lặp lại sau 50ms ~ 20fps
            }
        }, 50)
    }

    private fun loadECGData() {
        // Giả lập 1 số mẫu dữ liệu sóng ECG
        val ecgData = mutableListOf<Entry>()
        for (i in 0..199) {
            val y = (Math.sin(i * 0.1) * 100).toFloat() // sóng sin
            ecgData.add(Entry(i.toFloat(), y))
        }

        val dataSet = LineDataSet(ecgData, "ECG Signal").apply {
            color = Color.RED
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
        }

        ecgChart.data = LineData(dataSet)
        ecgChart.invalidate() // vẽ lại
    }
//=============================================================================
}

