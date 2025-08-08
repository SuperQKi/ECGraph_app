package com.example.ecgraph

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ecgRecord (){
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ecg_record) // to call layout record_ecg.xml
        //setContentView(R.layout.analysis_results)
        ecgChart = findViewById(R.id.ecgChart) // trỏ đến ecgChart trong layout
        ecgChart.setNoDataText("")
        //setupChart()
        val btnStart = findViewById<Button>(R.id.btnStart)
        btnStart.setOnClickListener {
            setupChart()
        }
        val btnStop = findViewById<Button>(R.id.btnStop)
        btnStop.setOnClickListener {
            val intent = Intent(this, AnalysisActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}

