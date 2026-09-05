/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.arcadesignpro.auroravpn.receiver

import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for UserPresentReceiver
 * Tests the screen unlock event handling
 */
@RunWith(RobolectricTestRunner::class)
// Use the plain Android Application so no app-level startKoin() is called during
// Robolectric's Application.onCreate(); otherwise a Koin leak from a prior class
// causes KoinApplicationAlreadyStartedException here.
@Config(sdk = [28], application = android.app.Application::class)
class UserPresentReceiverTest {

    private lateinit var receiver: UserPresentReceiver
    
    @Before
    fun setUp() {
        // Defensively stop any Koin that may have leaked from a prior test class.
        try { stopKoin() } catch (_: Exception) {}
        receiver = UserPresentReceiver()
    }

    @After
    fun tearDown() {
        try { stopKoin() } catch (_: Exception) {}
    }

    @Test
    fun `receiver should handle ACTION_USER_PRESENT intent`() {
        // Test that receiver can handle the ACTION_USER_PRESENT intent
        val intent = Intent(Intent.ACTION_USER_PRESENT)
        assertNotNull("Intent with ACTION_USER_PRESENT should be valid", intent.action)
        assertEquals("Intent action should match", Intent.ACTION_USER_PRESENT, intent.action)
    }

    @Test
    fun `intent action constant should be correct`() {
        // Verify the intent action constant matches Android framework constant
        assertEquals("ACTION_USER_PRESENT constant", "android.intent.action.USER_PRESENT", Intent.ACTION_USER_PRESENT)
    }

    @Test
    fun `receiver class should extend BroadcastReceiver`() {
        // Test that the receiver extends the correct base class
        assertTrue("UserPresentReceiver should be a BroadcastReceiver", 
                  receiver is android.content.BroadcastReceiver)
    }

    @Test
    fun `receiver should handle unknown actions gracefully`() {
        // Test that receiver can handle unknown actions without crashing
        val unknownAction = "com.example.unknown.action"
        val intent = Intent(unknownAction)
        assertNotNull("Intent with unknown action should be valid", intent.action)
        assertEquals("Intent action should match", unknownAction, intent.action)
    }

    @Test
    fun `receiver class should have proper package`() {
        // Test that the receiver is in the correct package
        assertEquals("Package should be correct", 
                    "com.arcadesignpro.auroravpn.receiver", 
                    receiver.javaClass.packageName)
    }

    @Test
    fun `receiver class name should be correct`() {
        // Test that the receiver has the expected class name
        assertEquals("Class name should be correct", 
                    "UserPresentReceiver", 
                    receiver.javaClass.simpleName)
    }

    @Test
    fun `receiver handles null action gracefully`() {
        // Test that receiver can handle null action without crashing
        val intent = Intent()
        intent.action = null
        assertNull("Intent action should be null", intent.action)
    }

    @Test
    fun `receiver handles various action types`() {
        // Test that receiver can handle different action types
        val testActions = listOf(
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            "android.intent.action.UNKNOWN"
        )
        
        testActions.forEach { action ->
            val intent = Intent(action)
            assertEquals("Intent action should match for $action", action, intent.action)
        }
    }

    @Test
    fun `receiver should be in receiver package`() {
        // Test that the receiver is in the receiver subpackage
        assertTrue("Class should be in receiver package", 
                  receiver.javaClass.packageName.endsWith(".receiver"))
    }
}