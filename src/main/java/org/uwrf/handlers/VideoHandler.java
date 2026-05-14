package org.uwrf.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.uwrf.services.BedrockQuizGenerator;
import org.uwrf.services.MockQuizGenerator;
import org.uwrf.services.QuizGenerator;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.*;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public class VideoHandler implements RequestHandler<S3Event, String> {

    private final QuizGenerator quizGenerator;

    public VideoHandler() {
        this("true".equalsIgnoreCase(System.getenv("MOCK_BEDROCK"))
                ? new MockQuizGenerator()
                : new BedrockQuizGenerator());
    }

    VideoHandler(QuizGenerator quizGenerator) {
        this.quizGenerator = quizGenerator;
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        System.out.println("=== Lambda Function Triggered ===");
        System.out.println("Received S3 event with " + s3Event.getRecords().size() + " record(s)");

        ObjectMapper mapper = new ObjectMapper();
        S3Client s3Client = S3Client.create();
        TranscribeClient transcribeClient = TranscribeClient.create();

        for (S3EventNotification.S3EventNotificationRecord record : s3Event.getRecords()) {
            String bucketName = record.getS3().getBucket().getName();
            String objectKey = record.getS3().getObject().getKey();
            long objectSize = record.getS3().getObject().getSizeAsLong() != null ? record.getS3().getObject().getSizeAsLong() : 0L;
            String eventName = record.getEventName();

            System.out.println("--- S3 Event Details ---");
            System.out.println("Event Type: " + eventName);
            System.out.println("Bucket: " + bucketName);
            System.out.println("File: " + objectKey);
            System.out.println("Size: " + objectSize + " bytes");
            System.out.println("Event Time: " + record.getEventTime());
            System.out.println("------------------------");

            try {
                String transcriptKey;

                boolean mockTranscribe = "true".equalsIgnoreCase(System.getenv("MOCK_TRANSCRIBE"));

                if (mockTranscribe) {
                    // Workaround: skip live AWS Transcribe and read a pre-uploaded transcript JSON.
                    // Per instructor guidance (Canvas announcement, May 2026), AWS Transcribe was
                    // not functioning reliably. Upload your transcript JSON to S3 at the key
                    // specified by the TRANSCRIPT_KEY environment variable (default: transcripts/workaround.json)
                    // before triggering the pipeline.
                    transcriptKey = System.getenv("TRANSCRIPT_KEY") != null
                            ? System.getenv("TRANSCRIPT_KEY")
                            : "transcripts/workaround.json";
                    System.out.println("[MOCK_TRANSCRIBE] Skipping live Transcribe job.");
                    System.out.println("[MOCK_TRANSCRIBE] Reading pre-uploaded transcript from: " + transcriptKey);
                } else {
                    // Step 1 - Start Transcribe job
                    String jobName = "quiz-" + UUID.randomUUID();
                    String mediaUri = "s3://" + bucketName + "/" + objectKey;
                    transcriptKey = "transcripts/" + jobName + ".json";

                    System.out.println("Starting transcription job: " + jobName);
                    transcribeClient.startTranscriptionJob(StartTranscriptionJobRequest.builder()
                            .transcriptionJobName(jobName)
                            .media(Media.builder().mediaFileUri(mediaUri).build())
                            .mediaFormat(MediaFormat.MP4)
                            .languageCode(LanguageCode.EN_US)
                            .outputBucketName(bucketName)
                            .outputKey(transcriptKey)
                            .build());

                    // Step 2 - Poll until transcription is complete
                    TranscriptionJobStatus status = TranscriptionJobStatus.IN_PROGRESS;
                    while (status == TranscriptionJobStatus.IN_PROGRESS) {
                        System.out.println("Waiting for transcription to complete...");
                        Thread.sleep(15000); // wait 15 seconds between polls
                        GetTranscriptionJobResponse jobResponse = transcribeClient.getTranscriptionJob(
                                GetTranscriptionJobRequest.builder()
                                        .transcriptionJobName(jobName)
                                        .build());
                        status = jobResponse.transcriptionJob().transcriptionJobStatus();
                        System.out.println("Transcription status: " + status);
                    }

                    if (status != TranscriptionJobStatus.COMPLETED) {
                        System.out.println("Transcription failed with status: " + status);
                        continue;
                    }
                }

                // Step 3 - Read transcript from S3
                System.out.println("Reading transcript from S3...");
                ResponseInputStream<GetObjectResponse> transcriptStream = s3Client.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucketName)
                                .key(transcriptKey)
                                .build());

                JsonNode transcriptJson = mapper.readTree(transcriptStream);
                String transcript = transcriptJson
                        .get("results")
                        .get("transcripts")
                        .get(0)
                        .get("transcript")
                        .asText();

                System.out.println("Transcript length: " + transcript.length() + " characters");

                // Step 4 - Generate quiz from transcript
                System.out.println("Generating quiz...");
                String quizJson = this.quizGenerator.generateQuiz(transcript);

                // Step 5 - Build final quiz object with metadata
                String videoName = objectKey.contains("/")
                        ? objectKey.substring(objectKey.lastIndexOf("/") + 1)
                        : objectKey;
                String videoNameNoExt = videoName.replace(".mp4", "");

                ObjectNode quizObject = mapper.createObjectNode();
                quizObject.put("sourceVideo", objectKey);
                quizObject.put("generatedAt", Instant.now().toString());
                quizObject.set("questions", mapper.readTree(quizJson));

                String quizOutputKey = "quizzes/" + videoNameNoExt + "-quiz.json";
                String quizOutput = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(quizObject);

                // Step 6 - Write quiz JSON back to S3
                System.out.println("Writing quiz to S3: " + quizOutputKey);
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucketName)
                                .key(quizOutputKey)
                                .contentType("application/json")
                                .build(),
                        RequestBody.fromString(quizOutput));

                System.out.println("Quiz successfully written to s3://" + bucketName + "/" + quizOutputKey);

            } catch (Exception e) {
                System.out.println("ERROR processing " + objectKey + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        return "Processed " + s3Event.getRecords().size() + " record(s)";
    }
}
