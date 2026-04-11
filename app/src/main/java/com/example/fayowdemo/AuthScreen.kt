package com.example.fayowdemo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.fayowdemo.auth.AuthActions

@Composable
fun AuthScreen(authActions: AuthActions) {  // ✅ AuthActions est maintenant reconnu
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLogin by remember { mutableStateOf(true) }
    Image(
        painter = painterResource(id = R.drawable.fayow_fond),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        contentDescription = "Image de Fond FaYoW"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = if (isLogin) "Connexion" else "Inscription",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.inversePrimary ,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        TextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (isLogin) {
                    authActions.onSignIn(email, password)  // ✅ onSignIn reconnu
                } else {
                    authActions.onSignUp(email, password)  // ✅ onSignUp reconnu
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isLogin) "Se connecter" else "S'inscrire",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.inversePrimary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { isLogin = !isLogin }
        ) {
            Text(
                text = if (isLogin) "Créer un compte" else "J'ai déjà un compte",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.inversePrimary
            )
        }
    }
}