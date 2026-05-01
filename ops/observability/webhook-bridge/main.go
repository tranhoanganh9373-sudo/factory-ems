package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// AlertmanagerPayload is the subset of the Alertmanager webhook payload we need
// to translate into 钉钉 / 企微 native message format.
type AlertmanagerPayload struct {
	Status   string                   `json:"status"`
	Receiver string                   `json:"receiver"`
	Alerts   []map[string]interface{} `json:"alerts"`
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/dingtalk", handleDingtalk)
	mux.HandleFunc("/wechat", handleWechat)

	addr := ":9094"
	log.Printf("webhook-bridge listening on %s", addr)
	server := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server error: %v", err)
	}
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

func handleDingtalk(w http.ResponseWriter, r *http.Request) {
	rawURL := os.Getenv("OBS_DINGTALK_WEBHOOK")
	secret := os.Getenv("OBS_DINGTALK_SECRET")
	if rawURL == "" {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var p AlertmanagerPayload
	if err := json.Unmarshal(body, &p); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	signedURL := signDingtalk(rawURL, secret)
	payload := buildDingtalkPayload(p)
	postWithRetry(signedURL, payload, w)
}

func handleWechat(w http.ResponseWriter, r *http.Request) {
	rawURL := os.Getenv("OBS_WECHAT_WEBHOOK")
	if rawURL == "" {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var p AlertmanagerPayload
	if err := json.Unmarshal(body, &p); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	payload := buildWechatPayload(p)
	postWithRetry(rawURL, payload, w)
}

func formatAlertText(p AlertmanagerPayload) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("[factory-ems] status=%s alerts=%d\n", p.Status, len(p.Alerts)))
	for _, a := range p.Alerts {
		labels, _ := a["labels"].(map[string]interface{})
		annot, _ := a["annotations"].(map[string]interface{})
		alertname := str(labels["alertname"])
		severity := str(labels["severity"])
		summary := str(annot["summary"])
		instance := str(labels["instance"])
		status := str(a["status"])
		sb.WriteString(fmt.Sprintf("- [%s] %s severity=%s instance=%s\n  %s\n",
			status, alertname, severity, instance, summary))
	}
	return sb.String()
}

func str(v interface{}) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprintf("%v", v)
}

func buildDingtalkPayload(p AlertmanagerPayload) []byte {
	body, _ := json.Marshal(map[string]interface{}{
		"msgtype": "text",
		"text": map[string]string{
			"content": formatAlertText(p),
		},
	})
	return body
}

func buildWechatPayload(p AlertmanagerPayload) []byte {
	body, _ := json.Marshal(map[string]interface{}{
		"msgtype": "text",
		"text": map[string]string{
			"content": formatAlertText(p),
		},
	})
	return body
}

// signDingtalk appends timestamp + HMAC-SHA256 signature query params
// per https://open.dingtalk.com/document/robots/customize-robot-security-settings
func signDingtalk(rawURL, secret string) string {
	if secret == "" {
		return rawURL
	}
	ts := time.Now().UnixMilli()
	signStr := fmt.Sprintf("%d\n%s", ts, secret)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(signStr))
	signed := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	signedEsc := url.QueryEscape(signed)

	parsed, err := url.Parse(rawURL)
	if err != nil {
		return rawURL
	}
	q := parsed.Query()
	q.Set("timestamp", fmt.Sprintf("%d", ts))
	q.Set("sign", signedEsc)
	parsed.RawQuery = q.Encode()
	return parsed.String()
}

// postWithRetry tries up to 3 times with linear backoff (0s/1s/2s).
// Writes 204 on first success, 502 on all-failed.
func postWithRetry(targetURL string, body []byte, w http.ResponseWriter) {
	backoffs := []time.Duration{0, 1 * time.Second, 2 * time.Second}
	var lastErr error
	for i, wait := range backoffs {
		if wait > 0 {
			time.Sleep(wait)
		}
		req, err := http.NewRequest(http.MethodPost, targetURL, bytes.NewReader(body))
		if err != nil {
			lastErr = err
			log.Printf("webhook attempt %d build req failed: %v", i+1, err)
			continue
		}
		req.Header.Set("Content-Type", "application/json")
		client := &http.Client{Timeout: 10 * time.Second}
		resp, err := client.Do(req)
		if err == nil && resp.StatusCode >= 200 && resp.StatusCode < 300 {
			resp.Body.Close()
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if resp != nil {
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			lastErr = fmt.Errorf("status=%d body=%s", resp.StatusCode, string(b))
		} else {
			lastErr = err
		}
		log.Printf("webhook attempt %d failed: %v", i+1, lastErr)
	}
	http.Error(w, fmt.Sprintf("all 3 attempts failed: %v", lastErr), http.StatusBadGateway)
}
