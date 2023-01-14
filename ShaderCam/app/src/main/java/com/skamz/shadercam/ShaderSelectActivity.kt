package com.skamz.shadercam

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.otaliastudios.cameraview.filter.BaseFilter
import com.skamz.shadercam.databinding.ActivityShaderSelectBinding


class ShaderSelectActivity: AppCompatActivity() {

    companion object {
        lateinit var cameraActivityIntent: Intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i("DEBUG", "Shader on  create")

        val viewBinding = ActivityShaderSelectBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        val arrayAdapter: ArrayAdapter<*>
        val shaders = mapOf<String, AbstractShader>(
            "Bright" to Shaders.Companion.BrightShader(),
        )

        var mListView = findViewById<ListView>(R.id.list_view)
        arrayAdapter = ArrayAdapter(this,
            R.layout.shader_list_item, shaders.keys.toTypedArray())
        mListView.adapter = arrayAdapter

        val cameraActivityIntent = Intent(this, CameraActivity::class.java)
        cameraActivityIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        mListView.onItemClickListener =
            AdapterView.OnItemClickListener { parent, view, position, id ->
                val name = mListView.getItemAtPosition(position) as String
                val filter = shaders[name]!!
                CameraActivity.shader = filter
//                cameraActivityIntent.putExtra("shader", filter.fragmentShader);
                startActivity(cameraActivityIntent)
            }

        val cameraLink = findViewById<Button>(R.id.camera_link);
        cameraLink.setOnClickListener {
            startActivity(cameraActivityIntent)
        }

    }

}