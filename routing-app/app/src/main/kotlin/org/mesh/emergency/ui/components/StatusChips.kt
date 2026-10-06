package org.mesh.emergency.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mesh.android.power.DutyCycleState
import mesh.delivery.DeliveryState
import org.mesh.emergency.ui.theme.Amber500
import org.mesh.emergency.ui.theme.Cyan400
import org.mesh.emergency.ui.theme.Emerald500
import org.mesh.emergency.ui.theme.Purple400
import org.mesh.emergency.ui.theme.Rose500
import org.mesh.emergency.ui.theme.Slate400

@Composable
fun DeliveryStatusChip(
    state: DeliveryState,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, label) = when (state) {
        DeliveryState.QUEUED -> Triple(Color(0xFF3B2D12), Amber500, "QUEUED")
        DeliveryState.SENT -> Triple(Color(0xFF0F3048), Cyan400, "SENT")
        DeliveryState.RELAYED -> Triple(Color(0xFF2E1A47), Purple400, "RELAYED")
        DeliveryState.DELIVERED -> Triple(Color(0xFF103A2B), Emerald500, "DELIVERED")
        DeliveryState.EXPIRED -> Triple(Color(0xFF3F141E), Rose500, "EXPIRED")
    }

    Box(
        modifier = modifier
            .background(bgColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(textColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun HopCountChip(
    hopCount: Int,
    isBroadcast: Boolean,
    modifier: Modifier = Modifier
) {
    val text = when {
        isBroadcast -> "📡 Broadcast"
        hopCount <= 1 -> "⚡ Direct (1 hop)"
        else -> "🔀 $hopCount hops"
    }

    Box(
        modifier = modifier
            .background(Color(0xFF1E293B), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = Slate400,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun DutyCycleBadge(
    state: DutyCycleState,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, label) = when (state) {
        DutyCycleState.ACTIVE -> Triple(Color(0xFF103A2B), Emerald500, "RADIO ACTIVE")
        DutyCycleState.IDLE_SCANNING -> Triple(Color(0xFF0F3048), Cyan400, "IDLE SCANNING")
        DutyCycleState.IDLE_SLEEPING -> Triple(Color(0xFF1E293B), Slate400, "IDLE SLEEPING")
    }

    Box(
        modifier = modifier
            .background(bgColor, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(textColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
