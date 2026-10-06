package mesh.android.permission

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeshPermissionsTest {

    private val context = mockk<Context>()

    @Test
    fun `getRequiredPermissions returns non-empty list of permissions`() {
        val permissions = MeshPermissions.getRequiredPermissions()
        assertTrue(permissions.isNotEmpty())
    }

    @Test
    fun `hasAllPermissions returns true when all permissions are granted`() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        val result = MeshPermissions.hasAllPermissions(context)
        assertTrue(result)
    }

    @Test
    fun `hasAllPermissions returns false when at least one permission is denied`() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED

        val result = MeshPermissions.hasAllPermissions(context)
        assertFalse(result)
    }
}
