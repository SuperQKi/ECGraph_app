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

class MainActivity : ecgRecord (){
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ecg_record) // to call layout record_ecg.xml
        ecgChart = findViewById(R.id.ecgChart) // trỏ đến ecgChart trong layout
        setupChart()
        loadECGData()
        startRealTimeUpdate()
    }
}

