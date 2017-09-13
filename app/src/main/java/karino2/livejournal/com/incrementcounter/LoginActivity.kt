package karino2.livejournal.com.incrementcounter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import java.net.MalformedURLException
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.stream.JsonWriter
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.Charset


class LoginActivity : AppCompatActivity() {

    companion object {
        fun getAppPreferences(ctx : Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        fun getUriArgFromPreferences(prefs: SharedPreferences): String {
            return prefs.getString("uri_arg", "")
        }

        fun getAccessTokenFromPreferences(prefs: SharedPreferences): String {
            return prefs.getString("access_token", "")
        }

    }

    val prefs : SharedPreferences by lazy { getAppPreferences(this) }

    fun showMessage(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    val webView by lazy { findViewById(R.id.webview) as WebView }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        if(intent != null) {
            if(intent.action == Intent.ACTION_MAIN) {
                prefs.edit()
                        .remove("url_arg")
                        .commit()
            } else if (intent.action == Intent.ACTION_SEND) {
                val uri = intent.getParcelableExtra<Uri?>(Intent.EXTRA_STREAM)
                if(uri == null) {
                    showMessage("not supported. getPercelableExtra fail.")
                    finish()
                    return
                }
                prefs.edit()
                        .putString("uri_arg", uri.toString())
                        .commit()
            }
        }


        with(webView.settings) {
            javaScriptEnabled = true
            blockNetworkImage = false
            loadsImagesAutomatically = true
        }

        webView.setWebViewClient(object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if(url?.startsWith(getString(R.string.redirect_uri)) ?: false) {
                    val code = Uri.parse(url).getQueryParameter("code")
                    code?.let {
                        getAccessToken(code)
                        return true
                    }
                }
                return super.shouldOverrideUrlLoading(view, url)
            }
        })

        checkValidTokenAndGotoTopIfValid()
    }

    val accessToken: String
        get() = getAccessTokenFromPreferences(prefs)

    val authorizeUrl: String
        get() =
            "https://github.com/login/oauth/authorize?client_id=${getString(R.string.client_id)}" +
                    "&scope=public_repo&redirect_uri=${getString(R.string.redirect_uri)}"


    internal inner class CheckTokenValidity(val accessToken: String, val resultListener: (String)->Unit, val onError:(String)->Unit) : AsyncTask<Any, String, Boolean>() {
        var url = "https://api.github.com/repos/karino2/karino2.github.io/readme"// "https://api.github.com/gists" // TODO: API issue

        var responseText = "success"
        var errorMessage = ""

        override fun doInBackground(vararg objects: Any): Boolean? {
            try {
                val u = URL(url)
                val connection = u.openConnection() as HttpURLConnection
                try {

                    connection.setRequestProperty("Authorization", "token " + accessToken)
                    connection.useCaches = false

                    return if (HttpURLConnection.HTTP_OK == connection.responseCode) true else false

                } finally {
                    connection.disconnect()
                }
            } catch (e: MalformedURLException) {
                errorMessage = "MalformedURLException: " + e.message
                return false
            } catch (e: IOException) {
                errorMessage = "IOException: " + e.message
                return false
            }

        }

        override fun onPostExecute(success: Boolean?) {
            if (success!!)
                resultListener(responseText)
            else
                onError(errorMessage)
        }
    }


    fun checkValidTokenAndGotoTopIfValid() {
        val accToken = accessToken
        if (accToken == "") {
            // not valid.
            webView.loadUrl(authorizeUrl)
            return
        }

        CheckTokenValidity(accToken, { afterLogin() }, {
            webView.loadUrl(authorizeUrl)
        }).execute()
    }


    data class AuthenticationJson(@SerializedName("access_token") val accessToken : String,
                                  @SerializedName("token_type") val tokenType : String,
                                  val scope: String
    )

    @Throws(IOException::class)
    fun readAll(inputStream: InputStream): String {
        val reader: BufferedReader
        reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
        val builder = StringBuilder()
        var line = reader.readLine()
        var first = true
        while (line != null) {
            if (!first)
                builder.append("\n")
            first = false
            builder.append(line)
            line = reader.readLine()
        }
        return builder.toString()
    }


    inner class GetAccessTokenTask(val code: String, val resultListener: (String)->Unit) : AsyncTask<Object, String, Boolean>() {
        val url =
                "https://github.com/login/oauth/access_token?client_id=${getString(R.string.client_id)}&client_secret=${getString(R.string.client_secret)}&code=$code"

        var responseText = ""
        var errorMessage = ""

        override fun doInBackground(vararg p0: Object?): Boolean {
            try{
                val u = URL(url)
                val connection = u.openConnection() as HttpURLConnection

                try {
                    with(connection) {
                        setRequestProperty("Accept", "application/json")
                        useCaches = false
                        doInput = true
                        doOutput = true
                    }

                    val body = readAll(connection.inputStream)
                    val gson = Gson()

                    responseText = gson.fromJson(body, AuthenticationJson::class.java).accessToken
                    return true
                }finally {
                    connection.disconnect()
                }

            }catch (e : MalformedURLException) {
                errorMessage = "MalformedURLException: ${e.message}"
                return false;
            } catch(e: IOException) {
                errorMessage = "IOException: ${e.message}"
                return false;
            }
        }

        override fun onPostExecute(result: Boolean) {
            if(result)
                resultListener(responseText)
            else
                showMessage("getAuthTask fail: $errorMessage")
        }

    }

    private fun getAccessToken(code: String) {
        GetAccessTokenTask(code) {resp ->
            prefs.edit()
                    .putString("access_token", resp)
                    .commit()
            afterLogin()
        }.execute()
    }

    val jsonMedia by lazy { MediaType.parse("application/json")}

    fun requestBodyBuilder(builder: JsonWriter.()->Unit) : RequestBody {
        val sw = StringWriter()
        val jw = JsonWriter(sw)
        jw.builder()
        val jsonstr = sw.toString()
        return RequestBody.create(jsonMedia, jsonstr)
    }

    val token : String by lazy { prefs.getString("access_token", "") }

    fun requestWithTokenBuilder() = Request.Builder().addHeader("Authorization", "token $token")


    data class Content(val content : String, val sha : String)

    fun secondRequest(client: OkHttpClient, content: Content, newCount: Int) : Response {


        val reqBody = requestBodyBuilder {
            beginObject()
                    .name("path").value("counter.txt")
                    .name("message").value("Update from IncrementCounter")
                    .name("content").value(Base64.encodeToString(newCount.toString().toByteArray(), Base64.DEFAULT))
                    .name("sha").value(content.sha)
                    .name("branch").value("build")
        }

        val request = requestWithTokenBuilder()
                .url("https://api.github.com/repos/karino2/karino2.github.io/contents/counter.txt")
                .put(reqBody)
                .build()
        return client.newCall(request).execute()
    }

    private fun afterLogin() {
        val client = OkHttpClient()

        val gson = Gson()

        val request = requestWithTokenBuilder()
                .url("https://api.github.com/repos/karino2/karino2.github.io/contents/counter.txt?ref=build")
                .get()
                .build()


        Single.fromCallable { client.newCall(request).execute() }
                .map { resp -> resp.body()!!.string() }
                .map { respBody -> gson.fromJson(respBody, Content::class.java)}
                .map { cont -> Pair(cont, String(Base64.decode(cont.content, Base64.DEFAULT))) }
                .map { (cont, countStr) ->
                    // countStr contains \n.
                    val range = if(countStr[countStr.length-1]  == '\n') 0 until (countStr.length -1) else 0 until countStr.length
                    val newCount = countStr.slice(range).toInt()+1
                    Pair(cont, newCount)
                }
                .map { (cont, newCount) -> secondRequest(client, cont, newCount) }
                .map { resp-> resp.isSuccessful }
                .subscribeOn(Schedulers.single())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { res ->
                    showMessage( if (res) "Update Success" else "Update Fail" )
                    finish()
                }


    }
}
