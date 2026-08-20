package com.carfam.blemeshchat

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var service: BleMeshService? = null
    private var bound = false

    private val messages = mutableListOf<UiMessage>()
    private lateinit var adapter: MessageAdapter
    private lateinit var statusText: TextView
    private lateinit var messageInput: EditText
    private lateinit var recyclerView: RecyclerView

    private var announcementDialog: AlertDialog? = null

    data class UiMessage(
        val id: String,
        val sender: String,
        var text: String,
        val timestamp: Long,
        val isMe: Boolean,
        var edited: Boolean = false
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.entries.filter { it.key != android.Manifest.permission.POST_NOTIFICATIONS }
                .all { it.value }) {
            bindAndStartService()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required for mesh chat", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BleMeshService.LocalBinder).getService()
            bound = true
            service?.startMeshing()
            statusText.text = "You: ${service?.getLocalId()} | Peers: 0"
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleMeshService.ACTION_MESSAGE_EVENT -> {
                    val type = intent.getStringExtra(BleMeshService.EXTRA_TYPE) ?: return
                    val id = intent.getStringExtra(BleMeshService.EXTRA_ID) ?: return
                    val sender = intent.getStringExtra(BleMeshService.EXTRA_SENDER) ?: return
                    val targetId = intent.getStringExtra(BleMeshService.EXTRA_TARGET_ID) ?: ""
                    val text = intent.getStringExtra(BleMeshService.EXTRA_TEXT) ?: ""
                    val ts = intent.getLongExtra(BleMeshService.EXTRA_TIMESTAMP, System.currentTimeMillis())
                    val isMe = sender == service?.getLocalId()

                    when (type) {
                        MeshMessage.TYPE_MSG -> {
                            messages.add(UiMessage(id, sender, text, ts, isMe))
                            adapter.notifyItemInserted(messages.size - 1)
                            recyclerView.scrollToPosition(messages.size - 1)
                        }
                        MeshMessage.TYPE_EDIT -> {
                            val idx = messages.indexOfFirst { it.id == targetId }
                            if (idx >= 0) {
                                messages[idx].text = text
                                messages[idx].edited = true
                                adapter.notifyItemChanged(idx)
                            }
                        }
                        MeshMessage.TYPE_DELETE -> {
                            val idx = messages.indexOfFirst { it.id == targetId }
                            if (idx >= 0) {
                                messages.removeAt(idx)
                                adapter.notifyItemRemoved(idx)
                            }
                        }
                    }
                }
                BleMeshService.ACTION_PEER_COUNT_CHANGED -> {
                    val count = intent.getIntExtra(BleMeshService.EXTRA_PEER_COUNT, 0)
                    statusText.text = "You: ${service?.getLocalId()} | Peers: $count"
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
        recyclerView = findViewById(R.id.messageList)

        adapter = MessageAdapter(
            items = messages,
            onLongPress = { msg -> showMessageOptions(msg) }
        )
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
        setupAnnouncementListener()
        subscribeToAnnouncementsTopic()
    }

    // ---------------------------------------------------------------
    // Remote announcement (controlled from the Firebase console)
    // ---------------------------------------------------------------

    private fun setupAnnouncementListener() {
        Firebase.firestore.collection("announcements").document("current")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val active = snapshot.getBoolean("active") ?: false
                val description = snapshot.getString("description") ?: ""
                val link = snapshot.getString("link") ?: ""

                if (active) {
                    showAnnouncementDialog(description, link)
                } else {
                    announcementDialog?.dismiss()
                    announcementDialog = null
                }
            }
    }

    private fun showAnnouncementDialog(description: String, link: String) {
        announcementDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage(description)
            .setCancelable(false)
            .setPositiveButton("Download") { _, _ ->
                if (link.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }
            }
            .create()
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        dialog.show()
        announcementDialog = dialog
    }

    private fun subscribeToAnnouncementsTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic("all_users")
    }

    // ---------------------------------------------------------------
    // Message long-press menu
    // ---------------------------------------------------------------

    private fun showMessageOptions(msg: UiMessage) {
        val options = if (msg.isMe) {
            arrayOf("Edit", "Delete for me", "Delete for everyone")
        } else {
            arrayOf("Delete for me")
        }
        AlertDialog.Builder(this)
            .setTitle("Message options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Edit" -> showEditDialog(msg)
                    "Delete for me" -> deleteForMe(msg)
                    "Delete for everyone" -> deleteForEveryone(msg)
                }
            }
            .show()
    }

    private fun showEditDialog(msg: UiMessage) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(msg.text)
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle("Edit message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty() && newText != msg.text) {
                    service?.sendEdit(msg.id, newText)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteForMe(msg: UiMessage) {
        val idx = messages.indexOfFirst { it.id == msg.id }
        if (idx >= 0) {
            messages.removeAt(idx)
            adapter.notifyItemRemoved(idx)
        }
    }

    private fun deleteForEveryone(msg: UiMessage) {
        service?.sendDeleteForEveryone(msg.id)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun bindAndStartService() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Please turn on Bluetooth first", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, BleMeshService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val filter = IntentFilter().apply {
            addAction(BleMeshService.ACTION_MESSAGE_EVENT)
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

    class MessageAdapter(
        private val items: List<UiMessage>,
        private val onLongPress: (UiMessage) -> Unit
    ) : RecyclerView.Adapter<MessageAdapter.VH>() {

        companion object {
            private const val TYPE_SENT = 0
            private const val TYPE_RECEIVED = 1
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val sender: TextView? = view.findViewById(R.id.senderText)
            val body: TextView = view.findViewById(R.id.bodyText)
            val time: TextView = view.findViewById(R.id.timeText)
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position].isMe) TYPE_SENT else TYPE_RECEIVED

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (viewType == TYPE_SENT) R.layout.item_message_sent else R.layout.item_message_received
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
            holder.sender?.text = item.sender
            holder.body.text = item.text
            holder.time.text = if (item.edited) "$time . edited" else time

            holder.itemView.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
