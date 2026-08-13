package com.carfam.blemeshchat

import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var service: BleMeshService? = null
    private var bound = false

    private val messages = mutableListOf<UiMessage>()
    private lateinit var adapter: MessageAdapter
    private lateinit var statusText: TextView
    private lateinit var messageInput: EditText

    data class UiMessage(val sender: String, val text: String, val timestamp: Long, val isMe: Boolean)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            bindAndStartService()
        } else {
            Toast.makeText(this, "Bluetooth permissions zaroori hain mesh chat ke liye", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BleMeshService.LocalBinder).getService()
            bound = true
            service?.startMeshing()
            statusText.text = "You are: ${service?.getLocalId()} | Peers: 0"
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleMeshService.ACTION_MESSAGE_RECEIVED -> {
                    val sender = intent.getStringExtra(BleMeshService.EXTRA_SENDER) ?: return
                    val text = intent.getStringExtra(BleMeshService.EXTRA_TEXT) ?: return
                    val ts = intent.getLongExtra(BleMeshService.EXTRA_TIMESTAMP, System.currentTimeMillis())
                    val isMe = sender == service?.getLocalId()
                    messages.add(UiMessage(sender, text, ts, isMe))
                    adapter.notifyItemInserted(messages.size - 1)
                }
                BleMeshService.ACTION_PEER_COUNT_CHANGED -> {
                    val count = intent.getIntExtra(BleMeshService.EXTRA_PEER_COUNT, 0)
                    statusText.text = "You are: ${service?.getLocalId()} | Peers: $count"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        messageInput = findViewById(R.id.messageInput)
        val sendButton: Button = findViewById(R.id.sendButton)
        val recyclerView: RecyclerView = findViewById(R.id.messageList)

        adapter = MessageAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                service?.sendMessage(text)
                messageInput.setText("")
            }
        }

        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(android.Manifest.permission.BLUETOOTH_SCAN)
            perms.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun bindAndStartService() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Kripya pehle Bluetooth ON karein", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, BleMeshService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val filter = IntentFilter().apply {
            addAction(BleMeshService.ACTION_MESSAGE_RECEIVED)
            addAction(BleMeshService.ACTION_PEER_COUNT_CHANGED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        super.onDestroy()
    }

    class MessageAdapter(private val items: List<UiMessage>) :
        RecyclerView.Adapter<MessageAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val sender: TextView = view.findViewById(R.id.senderText)
            val body: TextView = view.findViewById(R.id.bodyText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
            holder.sender.text = if (item.isMe) "You · $time" else "${item.sender} · $time"
            holder.body.text = item.text
        }

        override fun getItemCount() = items.size
    }
}
