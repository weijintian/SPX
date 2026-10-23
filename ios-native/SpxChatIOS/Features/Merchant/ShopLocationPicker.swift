import SwiftUI
import MapKit

struct ShopLocationPicker: View {
    let initialAddress: String
    let initialCoordinate: MapCoordinate?
    let selected: (String, MapCoordinate) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var address = ""
    @State private var coordinate: MapCoordinate?
    @State private var camera: MapCameraPosition = .automatic
    @State private var results: [MKMapItem] = []
    @State private var searching = false
    @State private var searchID = UUID()
    @State private var error: String?
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                MapReader { proxy in
                    Map(position: $camera) {
                        if let coordinate { Marker("门店位置", coordinate: CLLocationCoordinate2D(latitude: coordinate.latitude, longitude: coordinate.longitude)).tint(SPXColor.primary) }
                    }.onTapGesture { point in
                        if let value = proxy.convert(point, from: .local) { coordinate = MapCoordinate(latitude: value.latitude, longitude: value.longitude); results = [] }
                    }
                }.frame(height: 280)
                Form {
                    Section("门店地址") {
                        TextField("输入城市、街道和门牌号", text: $address, axis: .vertical).lineLimit(2...4)
                        Text("搜索地址后选择结果，或轻点地图调整门店位置。").font(.caption).foregroundStyle(.secondary)
                        if let coordinate { InfoRow(title: "经纬度", value: String(format: "%.6f, %.6f", coordinate.latitude, coordinate.longitude)) }
                    }
                    if searching { ProgressView("正在搜索…") }
                    if !results.isEmpty {
                        Section("搜索结果") {
                            ForEach(Array(results.enumerated()), id: \.offset) { _, item in
                                Button { choose(item) } label: { VStack(alignment: .leading, spacing: 5) { Text(item.name ?? "地点").foregroundStyle(.primary); Text(item.placemark.title ?? "").font(.caption).foregroundStyle(.secondary) } }
                            }
                        }
                    }
                    if let error { ErrorBanner(message: error) }
                }
            }.navigationTitle("选择门店位置").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $query, prompt: "搜索城市与门店")
                .onSubmit(of: .search) { Task { await search() } }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("使用此位置") { if let coordinate { selected(address.trimmingCharacters(in: .whitespacesAndNewlines), coordinate); dismiss() } }.disabled(coordinate == nil || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
                }
                .onAppear {
                    address = initialAddress; query = initialAddress; coordinate = initialCoordinate
                    if let coordinate { center(coordinate) }
                }
        }
    }
    private func center(_ value: MapCoordinate) { camera = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: value.latitude, longitude: value.longitude), span: MKCoordinateSpan(latitudeDelta: 0.015, longitudeDelta: 0.015))) }
    private func choose(_ item: MKMapItem) {
        let value = item.placemark.coordinate
        let point = MapCoordinate(latitude: value.latitude, longitude: value.longitude)
        coordinate = point; address = item.placemark.title ?? item.name ?? address; results = []; center(point)
    }
    private func search() async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let id = UUID(); searchID = id; searching = true
        defer { if id == searchID { searching = false } }
        do { let request = MKLocalSearch.Request(); request.naturalLanguageQuery = text; let response = try await MKLocalSearch(request: request).start(); guard id == searchID, !Task.isCancelled else { return }; results = response.mapItems; error = results.isEmpty ? "没有找到该地址，请补充城市或街道" : nil }
        catch { if id == searchID { self.error = error.localizedDescription } }
    }
}
